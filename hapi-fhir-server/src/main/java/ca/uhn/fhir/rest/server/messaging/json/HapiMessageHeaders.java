package ca.uhn.fhir.rest.server.messaging.json;

import ca.uhn.fhir.model.api.IModelJson;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.messaging.MessageHeaders;

import java.util.HashMap;
import java.util.Map;

/**
 * This class is for holding headers for BaseJsonMessages. Any serializable data can be thrown into
 * the header map. There are also three special headers, defined by the constants in this class, which are for use
 * in message handling retrying. There are also matching helper functions for fetching those special variables; however
 * they can also be accessed in standard map fashion with a `get` on the map.
 */
public class HapiMessageHeaders implements IModelJson {
    public static final String RETRY_COUNT_KEY = "retryCount";
    public static final String FIRST_FAILURE_KEY = "firstFailureTimestamp";
    public static final String LAST_FAILURE_KEY = "lastFailureTimestamp";

    @JsonProperty(RETRY_COUNT_KEY)
    private Integer myRetryCount = 0;
    @JsonProperty(FIRST_FAILURE_KEY)
    private Long myFirstFailureTimestamp;
    @JsonProperty(LAST_FAILURE_KEY)
    private Long myLastFailureTimestamp;

    @JsonProperty("customHeaders")
    private final Map<String, Object> headers;

    public HapiMessageHeaders(Map<String, Object> theHeaders) {
        headers = theHeaders;
    }

    public HapiMessageHeaders() {
        headers = new HashMap<>();
    }

    public Integer getRetryCount() {
    	return this.myRetryCount;
    }

    public Long getFirstFailureDate() {
    	return this.myFirstFailureTimestamp;
    }

    public Long getLastFailureDate() {
    	return this.myLastFailureTimestamp;
    }

    public void setRetryCount(Integer theRetryCount) {
    	this.myRetryCount = theRetryCount;
	 }

    public void setLastFailureDate(Long theLastFailureDate) {
    	this.myLastFailureTimestamp = theLastFailureDate;
	 }

	 public void setFirstFailureDate(Long theFirstFailureDate) {
    	this.myFirstFailureTimestamp = theFirstFailureDate;
	 }

    public Map<String, Object> getCustomHeaders() {
        return this.headers;
    }

    public MessageHeaders toMessageHeaders() {
		 Map<String, Object> returnedHeaders = new HashMap<>(this.headers);
    	returnedHeaders.put(RETRY_COUNT_KEY, myRetryCount);
	 	returnedHeaders.put(FIRST_FAILURE_KEY, myFirstFailureTimestamp);
	 	returnedHeaders.put(LAST_FAILURE_KEY, myLastFailureTimestamp);
	 	return new MessageHeaders(returnedHeaders);
	 }

}
