package net.floodlightcontroller.ddosdefence;

import java.util.ArrayList;
import java.util.Arrays;

import org.projectfloodlight.openflow.types.IPv4Address;
import org.restlet.data.Status;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class InitDefenceResource extends ServerResource {
	@Post
	public String store(String fmJson) {
		if(fmJson == null)
			return "Not valid payload";

		// interface with the Controller through a IDDoSDefenceREST reference
		IDDoSDefenceREST ddef =
				(IDDoSDefenceREST)getContext().getAttributes()
				.get(IDDoSDefenceREST.class.getCanonicalName());

		// parse JSON payload
		ObjectMapper mapper = new ObjectMapper();
		try {
			// get JSON root
			JsonNode root = mapper.readTree(fmJson);

			// get fields and check format
			int servicePort = root.get("serviceport").asInt();
			if(servicePort <= 0 || servicePort > 65535)
				throw new Exception("invalid servicePort value");

			// get pool of public addresses
			ArrayList<IPv4Address> castedAddresses = new ArrayList<IPv4Address>();
			final JsonNode arrNode = new ObjectMapper().readTree(fmJson).get("addresses");
			if(arrNode.isArray())
			    for (final JsonNode objNode : arrNode)
			    	castedAddresses.add(IPv4Address.of(objNode.asText()));
			else
				throw new Exception("invalid address list");
			
			if(castedAddresses.size() < 2)
				throw new Exception("too small address list (required at least 2 elements)");

			/*String[] addresses = new ObjectMapper().readValue(root.get("addresses").asText(), String[].class);*/

			// get threshold, which must not be zero or negative
			int threshold = root.get("threshold").asInt();
			if(threshold <= 0)
				throw new Exception("invalid threshold field");

			// initialize DDoSDefence module
			if(!ddef.initProtection(castedAddresses, servicePort, threshold))
				throw new Exception("cannot initialize controller");

		} catch (Exception e) {
			e.printStackTrace();
			setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			return e.toString();
		}

		return "";
	}
}
