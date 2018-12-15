package net.floodlightcontroller.ddosdefence;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IDDoSDefenceREST extends IFloodlightService {
	public String setEnableProtection(boolean enabled);
}