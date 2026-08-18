package ru.petstore.customer.repository;

import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import ru.petstore.customer.domain.Customer;

/**
 * Optional filters for the customer listing.
 */
public final class CustomerSpecifications {

    private static final char ESCAPE = '\\';

    private CustomerSpecifications() {
    }

    public static Specification<Customer> statusIs(Long id) {
        return id == null
                ? Specification.unrestricted()
                : (root, query, cb) -> cb.equal(root.get("status").get("id"), id);
    }

    /** Substring of an email, a first or a last name, case-insensitive. */
    public static Specification<Customer> matches(String text) {
        if (text == null || text.isBlank()) {
            return Specification.unrestricted();
        }
        String pattern = "%" + escaped(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("firstName")), pattern, ESCAPE),
                cb.like(cb.lower(root.get("lastName")), pattern, ESCAPE));
    }

    /**
     * Wildcards typed by the caller are literal characters, not a query language: unescaped,
     * a search for {@code %} would match every customer instead of none.
     */
    private static String escaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char symbol : text.toCharArray()) {
            if (symbol == ESCAPE || symbol == '%' || symbol == '_') {
                escaped.append(ESCAPE);
            }
            escaped.append(symbol);
        }
        return escaped.toString();
    }
}
