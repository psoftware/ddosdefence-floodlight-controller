package net.floodlightcontroller.ddosdefence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFType;
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
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.restserver.RestletRoutable;

public class DDoSDefence implements IOFMessageListener,IFloodlightModule,IDDoSDefenceREST {
	protected IFloodlightProviderService floodlightProvider;
	
	// REST Interface
	protected IRestApiService restApiService;

	// Statistics
	final static int CONNECTIONS_THRESHOLD = 100;
	int connectionCount = 0;
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

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPv4 ipv4 = (IPv4)eth.getPayload();
		
		// need to filter only http files?
	
		return Command.CONTINUE;
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
