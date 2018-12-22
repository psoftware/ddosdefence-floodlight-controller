package net.floodlightcontroller.ddosdefence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.web.ControllerSummaryResource;
import net.floodlightcontroller.core.web.ControllerSwitchesResource;
import net.floodlightcontroller.core.web.LoadedModuleLoaderResource;
import net.floodlightcontroller.learningswitch.ILearningSwitchService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.restserver.RestletRoutable;
import net.floodlightcontroller.util.FlowModUtils;

public class DDoSDefence implements IOFMessageListener,IFloodlightModule,IDDoSDefenceREST {
	protected IFloodlightProviderService floodlightProvider;
	
	// REST Interface
	protected IRestApiService restApiService;

	// L2 Switch table Interface
	protected ILearningSwitchService learningSwitch;

	// Parameters
	// TODO: those must be initialized... maybe using REST?
	IPv4Address protectedServiceAddressForwarded = null;
	IPv4Address protectedServiceAddressCurrent = IPv4Address.of("10.0.0.1");
	TransportPort protectedServicePort = TransportPort.of(80);

	// Statistics
	// TODO: must be this initialized using REST?
	final static int CONNECTIONS_THRESHOLD = 10;
	HashMap<IPv4Address, HashSet<TransportPort>> connectionListHM =
			new HashMap<IPv4Address, HashSet<TransportPort>>();
	boolean protectionEnabled = true;

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		learningSwitch = context.getServiceImpl(ILearningSwitchService.class);
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IRestApiService.class);
		l.add(ILearningSwitchService.class);
		return l;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IDDoSDefenceREST.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IDDoSDefenceREST.class, this);
		return m;
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		restApiService.addRestletRoutable(new DDoSDefenceWebRoutable());
	}

	@Override
	public String getName() {
		return DDoSDefence.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// LearningSwitch must be executed BEFORE this module to let
		// the switch auto-learn ports before DDoSDefence tries to figure out
		// the out port of a packet flow
		// TODO: fix, if possible, the hardcoded class name
		if(name.equals("learningswitch"))
			return true;
		return false;
	}

	boolean isTCPPacket(Ethernet eth) {
		// filter non IPv4 packets
		if(!(eth.getPayload() instanceof IPv4))
			return false;
		IPv4 ipv4Msg = (IPv4)eth.getPayload();

		//System.out.print("controller: packet is IPv4");

		// filter non TCP packets
		if(!(ipv4Msg.getPayload() instanceof TCP))
			return false;
		TCP tcpMsg = (TCP)ipv4Msg.getPayload();

		//System.out.println("controller: packet is TCP");
		System.out.println("controller: packet"
				+ " has src " + ipv4Msg.getSourceAddress().toString() + ":" + tcpMsg.getSourcePort().toString()
				+ " and dst " + ipv4Msg.getDestinationAddress().toString() + ":"  + tcpMsg.getDestinationPort().toString());
		return true;
	}

	boolean isClientToServerPacket(IPv4 ipv4Msg, TCP tcpMsg) {
		// filter packets not sent to the server (at new address or old address)
		if(!ipv4Msg.getDestinationAddress().equals(protectedServiceAddressCurrent)
				&& !ipv4Msg.getDestinationAddress().equals(protectedServiceAddressForwarded))
			return false;

		// filter packets sent to other services
		if(!(tcpMsg.getDestinationPort().equals(protectedServicePort)))
			return false;

		return true;
	}

	boolean isServerToClientPacket(IPv4 ipv4Msg, TCP tcpMsg) {
		// filter packets not coming from the server address
		if(!ipv4Msg.getSourceAddress().equals(protectedServiceAddressCurrent))
			return false;

		// filter packets not coming from the server port
		if(!(tcpMsg.getSourcePort().equals(protectedServicePort)))
			return false;

		return true;
	}

	OFFlowAdd buildFlowAdd(IOFSwitch sw, OFPacketIn pi, Ethernet eth, IPv4Address srcAddr, TransportPort srcPort, IPv4Address dstAddr, boolean drop) {
		// new MATCH list (ipv4 traffic to the protected server)
		// add rule for (src:srcport -> dstaddress:address)
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
		if(srcPort != null)
			mb.setExact(MatchField.TCP_SRC, srcPort);
		if(srcAddr != null)
			mb.setExact(MatchField.IPV4_SRC, srcAddr);
		if(dstAddr != null)
			mb.setExact(MatchField.IPV4_DST, dstAddr);

		// new RULE
		OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
		fmb.setXid(pi.getXid());
		fmb.setOutPort(OFPort.CONTROLLER);
		fmb.setBufferId(pi.getBufferId());
		fmb.setCookie(U64.of(0));
		fmb.setPriority(FlowModUtils.PRIORITY_MAX);
		// TODO: Investigate timeout
		fmb.setHardTimeout(60);
		fmb.setIdleTimeout(60);

		// new ACTION LIST
		OFActions actions = sw.getOFFactory().actions();
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();

		// if rule is not drop then we must add a rule that switches packet to the correct
		// destination port. This port must be obtained from the L2 switching table (implemented by LearningSwitch)
		// otherwise don't add anything, empty action list means drop action
		// NOTE: OFPort.NORMAL cannot be used without an hybrid OpenFlow Switch
		if(!drop) {
			// learningSwitch.getFromPortMap() is thread safe (THANK GOD)
			OFPort macPort = learningSwitch.getFromPortMap(sw, eth.getDestinationMACAddress(), null);
			// if no switching entry is found, then packet must be flooded (as a normal L2 switch would do)
			if(macPort == null)
				macPort = OFPort.FLOOD;
			actionList.add(actions.output(macPort, Integer.MAX_VALUE));
		}

		// Rule can change IP destination address of packets
		/*OFOxms oxms = sw.getOFFactory().oxms();
		OFActionSetField setDlDst = actions.buildSetField()
			.setField(
				oxms.buildIpv4Dst()
				.setValue(protectedServiceAddress)
				.build()
				).build();
		actionList.add(setDlDst);*/

		// attach ACTION LIST to RULE
		fmb.setActions(actionList);
		fmb.setMatch(mb.build());

		System.out.println("controller: new buildFlowAdd rule created (with drop = " + Boolean.toString(drop) + ")");
		return fmb.build();
	}

	OFFlowDelete buildFlowDelete(IOFSwitch sw, OFPacketIn pi, IPv4Address srcAddr, TransportPort srcPort, IPv4Address dstAddr) {
		// new MATCH list
		// add rule for (src:srcport -> dstaddress:address)
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
		if(srcPort != null)
			mb.setExact(MatchField.TCP_SRC, srcPort);
		if(srcAddr != null)
			mb.setExact(MatchField.IPV4_SRC, srcAddr);
		if(dstAddr != null)
			mb.setExact(MatchField.IPV4_DST, dstAddr);

		OFFlowDelete.Builder fmb = sw.getOFFactory().buildFlowDelete();
		fmb.setBufferId(pi.getBufferId());
		fmb.setXid(pi.getXid());
		fmb.setMatch(mb.build());

		System.out.println("controller: new buildFlowDelete rule created");
		return fmb.build();
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		System.out.println("controller: Received new packet of type " + msg.getType().toString());

		OFPacketIn pi = (OFPacketIn)msg;
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		// filter non TCP packets
		if(!isTCPPacket(eth))
			return Command.CONTINUE;

		// get IPv4 and TCP headers
		IPv4 ipv4Msg = (IPv4)eth.getPayload();
		TCP tcpMsg = (TCP)ipv4Msg.getPayload();

		// check if packet source is the protected server
		if(isServerToClientPacket(ipv4Msg,tcpMsg)) {
			System.out.println("controller: packet sent from the server to a client");

			// create a new rule to allow the forwarding
			OFFlowAdd fAdd = buildFlowAdd(sw, pi, eth,
					ipv4Msg.getSourceAddress(), tcpMsg.getSourcePort(),
					ipv4Msg.getDestinationAddress(),
					false);
			sw.write(fAdd);

			// pipeline traversal can end here
			return Command.STOP;
		}

		// otherwise check if packet is coming from a client to the server
		if(!isClientToServerPacket(ipv4Msg,tcpMsg))
			return Command.CONTINUE;

		System.out.println("controller: packet sent from a client to the server");

		// Define a list of flow actions to send to the switch
		ArrayList<OFMessage> OFMessageList = new ArrayList<OFMessage>();

		HashSet<TransportPort> connList = connectionListHM.get(ipv4Msg.getSourceAddress());
		// if protectionEnabled, add current source port to the list for the current source address
		if(protectionEnabled) {
			// Create a new ArrayList HashMap entry if it doesn't exist
			if(connList == null) {
				connList = new HashSet<TransportPort>();
				connectionListHM.put(ipv4Msg.getSourceAddress(), connList);
			}

			// Add the current source port to the list on if not present
			boolean new_port_connection = connList.add(tcpMsg.getSourcePort());
			if(new_port_connection)
				System.out.println("controller: client " + ipv4Msg.getSourceAddress().toString()
						+ " has now " + connList.size() + " connection count");
			else
				System.out.println("controller: already counted client port");
		}

		// if current client connections are higher than threshold, build a drop rule
		if(connList != null && connList.size() > CONNECTIONS_THRESHOLD) {
			// add a drop rule for the current client src address
			OFFlowAdd fAdd = buildFlowAdd(sw, pi, eth,
					ipv4Msg.getSourceAddress(), null,
					ipv4Msg.getDestinationAddress(), true);
			OFMessageList.add(fAdd);

		} else { // otherwise build a forward rule
			OFFlowAdd fAdd = buildFlowAdd(sw, pi, eth,
					ipv4Msg.getSourceAddress(), tcpMsg.getSourcePort(),
					ipv4Msg.getDestinationAddress(),
					false);
			OFMessageList.add(fAdd);

			if(ipv4Msg.getDestinationAddress().equals(protectedServiceAddressCurrent)
					&& protectionEnabled
					&& connList != null && false) {
				// Add OFDelete for (current_srcaddr:* -> D)
				OFMessageList.add(buildFlowDelete(sw, pi,
						ipv4Msg.getSourceAddress(), null, protectedServiceAddressForwarded));
				// Add OFDelete for (D -> current_srcaddr:*)
				OFMessageList.add(buildFlowDelete(sw, pi,
						protectedServiceAddressForwarded, null, ipv4Msg.getSourceAddress()));
			}
		}

		// Send all the rules to the switch
		System.out.println("controller: Sending rules");
		sw.write(OFMessageList);

		return Command.STOP;
	}

	@Override
	public String setEnableProtection(boolean enabled) {
		protectionEnabled = enabled;
		if(protectionEnabled = false)
			protectedServiceAddressForwarded = null;

		// TODO: Return another address from a list of public IP addresses, as the requirements
		return "0.0.0.0";
	}

	public class DDoSDefenceWebRoutable implements RestletRoutable {

		@Override
		public Restlet getRestlet(Context context) {
			Router router = new Router(context);

			// controller summary stats REST resource
			router.attach("/controller/summary/json", ControllerSummaryResource.class);
			// loaded modules REST resource
			router.attach("/module/loaded/json", LoadedModuleLoaderResource.class);
			// connected switches REST resource
			router.attach("/module/loaded/json", ControllerSwitchesResource.class);

			// ==== Custom resources ====
			router.attach("/defence/json", EnableDefenceResource.class);

			return router;
		}

		@Override
		public String basePath() {
			return "/ddosdefence";
		}

	}
}
