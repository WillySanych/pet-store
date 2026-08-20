package ru.petstore.order.client;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The customer and the delivery address in one REST call. */
@Component
public class CustomerClient {

    public static final String UPSTREAM = "customer";

    private static final String DELIVERY_TARGET = "/api/v1/customers/{id}/delivery-target";

    private final RestClient restClient;
    private final UpstreamCall call;

    public CustomerClient(RestClient customerRestClient, UpstreamCall customerCall) {
        this.restClient = customerRestClient;
        this.call = customerCall;
    }

    public DeliveryTarget deliveryTarget(UUID customerId, UUID addressId) {
        return call.call(() -> {
            try {
                return restClient.get()
                        .uri(uri -> {
                            uri.path(DELIVERY_TARGET);
                            if (addressId != null) {
                                uri.queryParam("addressId", addressId);
                            }
                            return uri.build(customerId);
                        })
                        .retrieve()
                        .body(DeliveryTarget.class);
            } catch (HttpClientErrorException.NotFound e) {
                throw new UpstreamNotFoundException("Customer " + customerId
                        + (addressId == null ? " has no delivery address" : " has no address " + addressId), e);
            } catch (HttpClientErrorException e) {
                throw new UpstreamFailedException(UPSTREAM, e.getStatusCode().toString(), e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                throw new UpstreamUnavailableException(UPSTREAM, e.getMessage(), e);
            } catch (RestClientException e) {
                throw new UpstreamFailedException(UPSTREAM, String.valueOf(e.getMessage()), e);
            }
        });
    }
}
