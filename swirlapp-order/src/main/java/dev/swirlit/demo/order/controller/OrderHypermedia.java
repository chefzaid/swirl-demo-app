package dev.swirlit.demo.order.controller;

import java.util.Optional;
import java.util.stream.Stream;

import dev.swirlit.demo.order.domain.Order;
import dev.swirlit.demo.order.dto.OrderResponse;
import dev.swirlit.demo.order.dto.UpdateOrderRequest;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.hateoas.AffordanceModel.PayloadMetadata;
import org.springframework.hateoas.AffordanceModel.PropertyMetadata;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.config.EnableHypermediaSupport;
import org.springframework.hateoas.config.EnableHypermediaSupport.HypermediaType;
import org.springframework.hateoas.mediatype.Affordances;
import org.springframework.hateoas.mediatype.PropertyUtils;
import org.springframework.http.HttpMethod;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Optional HAL-FORMS representation of an order: the same fields as {@link OrderResponse}, plus links to
 * related resources and affordances that describe the edit and delete requests the server accepts.
 * Clients opt in with {@code Accept: application/prs.hal-forms+json}; plain JSON stays the default.
 */
@Configuration(proxyBeanMethods = false)
@EnableHypermediaSupport(type = {HypermediaType.HAL, HypermediaType.HAL_FORMS})
class OrderHypermedia {

    static EntityModel<OrderResponse> toModel(Order order) {
        Link self = Affordances.of(linkTo(OrderController.class).slash(order.getId()).withSelfRel())
                .afford(HttpMethod.PUT).withInput(writable(UpdateOrderRequest.class)).withName("updateOrder")
                .andAfford(HttpMethod.DELETE).withName("deleteOrder")
                .toLink();
        return EntityModel.of(OrderResponse.from(order), self,
                linkTo(OrderController.class).withRel("orders"),
                // The user service owns users; both services share one public origin behind the ingress.
                Link.of("/api/users/" + order.getUserId(), "user"));
    }

    /** Record components have no setters, so Spring HATEOAS would describe every request field as read-only. */
    private static PayloadMetadata writable(Class<?> requestType) {
        PayloadMetadata exposed = PropertyUtils.getExposedProperties(requestType);
        return new PayloadMetadata() {
            @Override
            public Stream<PropertyMetadata> stream() {
                return exposed.stream().map(WritableProperty::new);
            }

            @Override
            public Class<?> getType() {
                return requestType;
            }
        };
    }

    private record WritableProperty(PropertyMetadata delegate) implements PropertyMetadata {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public boolean isRequired() {
            return delegate.isRequired();
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public Optional<String> getPattern() {
            return delegate.getPattern();
        }

        @Override
        public ResolvableType getType() {
            return delegate.getType();
        }

        @Override
        public String getInputType() {
            return delegate.getInputType();
        }
    }
}
