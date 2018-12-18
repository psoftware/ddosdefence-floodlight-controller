package net.floodlightcontroller.ddosdefence;

import org.restlet.resource.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EnableDefenceResource extends ServerResource {
	@Post
	public String store(String fmJson) {
		if(fmJson == null)
			return "Not valid payload";

		// interface with the Controller through a IDDoSDefenceREST reference
		IDDoSDefenceREST ddef =
				(IDDoSDefenceREST)getContext().getAttributes()
				.get(IDDoSDefenceREST.class.getCanonicalName());

		// this method returns an ip address (string) as response
		String response = "Invalid request";

		// parse JSON payload
		ObjectMapper mapper = new ObjectMapper();
		try {
			// get JSON root
			JsonNode root = mapper.readTree(fmJson);

			// get enable field value
			boolean enabled = Boolean.parseBoolean(root.get("enabled").asText());
			response = ddef.setEnableProtection(enabled);

		} catch (Exception e) {
			e.printStackTrace();
		}

		return response;
	}
}
