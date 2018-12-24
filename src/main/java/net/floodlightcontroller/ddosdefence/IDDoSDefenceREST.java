package net.floodlightcontroller.ddosdefence;

import java.util.ArrayList;

import org.projectfloodlight.openflow.types.IPv4Address;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IDDoSDefenceREST extends IFloodlightService {
	public String setEnableProtection(boolean enabled);
	public boolean initProtection(ArrayList<IPv4Address> addresses, int servicePort, int threshold);
}