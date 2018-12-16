package net.floodlightcontroller.ddosdefence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
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
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.restserver.RestletRoutable;

public class DDoSDefence implements IOFMessageListener,IFloodlightModule,IDDoSDefenceREST {
	protected IFloodlightProviderService floodlightProvider;
	
	// REST Interface
	protected IRestApiService restApiService;

	// Parameters
	// TODO: those must be initialized... maybe using REST?
	IPv4Address protectedServiceAddressForwarded;
	IPv4Address protectedServiceAddressCurrent;
	TransportPort protectedServicePort;

	// Statistics
	final static int CONNECTIONS_THRESHOLD = 100;
	HashMap<IPv4Address, ArrayList<TransportPort>> connectionListHM =
			new HashMap<IPv4Address, ArrayList<TransportPort>>();
	boolean protectionEnabled = false;

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IRestApiService.class);
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
		// TODO Auto-generated method stub
		return false;
	}

	boolean isProtectedServicePacket(Ethernet eth) {
		// filter non IPv4 packets
		if(!(eth.getPayload() instanceof IPv4))
			return false;
		IPv4 ipv4Msg = (IPv4)eth.getPayload();

		// filter non TCP packets
		if(!(ipv4Msg.getPayload() instanceof TCP))
			return false;
		TCP tcpMsg = (TCP)ipv4Msg.getPayload();

		// filter packets not sent to the server (at new address or old address)
		if(!ipv4Msg.getDestinationAddress().equals(protectedServiceAddressCurrent)
				&& !ipv4Msg.getDestinationAddress().equals(protectedServiceAddressForwarded))
			return false;

		// filter packets sent to other services
		if(!(tcpMsg.getDestinationPort().equals(protectedServicePort)))
			return false;

		return true;
	}

	OFFlowAdd buildFlowAdd(IOFSwitch sw, OFPacketIn pi, IPv4Address srcAddr, TransportPort srcPort, IPv4Address dstAddr) {
		// new MATCH list (ipv4 traffic to the protected server)
		// add rule for (src:srcport -> dstaddress:address)
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.TCP_SRC, srcPort);
		mb.setExact(MatchField.IPV4_SRC, srcAddr);
		mb.setExact(MatchField.IPV4_DST, dstAddr);

		// new RULE
		OFFlowAdd.Builder fmb = sw.getOFFactory().buildFlowAdd();
		fmb.setBufferId(pi.getBufferId());
		fmb.setXid(pi.getXid());
		// TODO: Investigate timeout

		// new ACTION LIST
		OFActions actions = sw.getOFFactory().actions();
		ArrayList<OFAction> actionList = new ArrayList<OFAction>();
		OFOxms oxms = sw.getOFFactory().oxms();

		// Rule can change IP destination address of packets
		/*OFActionSetField setDlDst = actions.buildSetField()
				.setField(
					oxms.buildIpv4Dst()
					.setValue(protectedServiceAddress)
					.build()
					).build();
		actionList.add(setDlDst);*/

		// attach ACTION LIST to RULE
		fmb.setActions(actionList);
		fmb.setMatch(mb.build());

		return fmb.build();
	}

	OFFlowDelete buildFlowDelete(IOFSwitch sw, OFPacketIn pi, IPv4Address srcAddr, TransportPort srcPort, IPv4Address dstAddr) {
		// new MATCH list
		// add rule for (src:srcport -> dstaddress:address)
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
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

		return fmb.build();
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		OFPacketIn pi = (OFPacketIn)msg;

		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		// filter non TCP packets
		// TODO: this method must also handle packets transmitted by the server to the client
		if(!isProtectedServicePacket(eth))
			return Command.CONTINUE;

		IPv4 ipv4Msg = (IPv4)eth.getPayload();
		TCP tcpMsg = (TCP)ipv4Msg.getPayload();

		// Define a list of flow actions to send to the switch
		ArrayList<OFMessage> OFMessageList = new ArrayList<OFMessage>();

		ArrayList<TransportPort> connList = connectionListHM.get(ipv4Msg.getSourceAddress());
		// if protectionEnabled, add current source port to the list for the current source address
		if(protectionEnabled) {
			// Create a new ArrayList HashMap entry if it doesn't exist
			if(connList == null) {
				connList = new ArrayList<TransportPort>();
				connectionListHM.put(ipv4Msg.getSourceAddress(), connList);
			}

			// Add the current source port to the list
			connList.add(tcpMsg.getSourcePort());
		}

		// if current client connections are higher than threshold, build a drop rule
		if(connList != null && connList.size() > CONNECTIONS_THRESHOLD) {
			 // TODO: add drop rule
		} else { // otherwise build a forward rule
			OFFlowAdd fAdd = buildFlowAdd(sw, pi,
					ipv4Msg.getSourceAddress(), tcpMsg.getSourcePort(), ipv4Msg.getDestinationAddress());
			OFMessageList.add(fAdd);

			if(ipv4Msg.getDestinationAddress().equals(protectedServiceAddressCurrent) && connList != null) {
				// Add OFDelete for (current_srcaddr:* -> D)
				OFMessageList.add(buildFlowDelete(sw, pi,
						ipv4Msg.getSourceAddress(), null, protectedServiceAddressForwarded));
				// Add OFDelete for (D -> current_srcaddr:*)
				OFMessageList.add(buildFlowDelete(sw, pi,
						protectedServiceAddressForwarded, null, ipv4Msg.getSourceAddress()));
			}
		}

		// Send all the rules to the switch
		sw.write(OFMessageList);

		return Command.STOP;
	}

	@Override
	public String setEnableProtection(boolean enabled) {
		protectionEnabled = enabled;
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
