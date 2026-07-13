package com.vingame.bot.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the shape of the domain exception hierarchy introduced in
 * TECH_DEBT_PASS_2 (Item 4, AD-4.1 / AD-4.2). These are pure
 * type-relationship assertions — they guard the reparenting so that a future
 * accidental change back to {@code extends RuntimeException} (which would leave
 * the HTTP handler arms working but break any {@code catch (BotManagerException)}
 * a caller might add) is caught at test time rather than in production.
 */
@DisplayName("BotManagerException hierarchy")
class BotManagerExceptionHierarchyTest {

    @Test
    @DisplayName("BotManagerException is an abstract RuntimeException with no extra state")
    void baseIsAbstractRuntimeException() {
        assertThat(RuntimeException.class).isAssignableFrom(BotManagerException.class);
        assertThat(Modifier.isAbstract(BotManagerException.class.getModifiers()))
                .as("base must be abstract so callers instantiate a concrete subtype")
                .isTrue();
    }

    @Test
    @DisplayName("ResourceNotFoundException extends the BotManagerException base")
    void resourceNotFoundReparented() {
        assertThat(BotManagerException.class)
                .isAssignableFrom(ResourceNotFoundException.class);
        // Still a RuntimeException (unchecked) — reparenting must not have
        // accidentally made it checked.
        assertThat(RuntimeException.class)
                .isAssignableFrom(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("BadRequestException extends the BotManagerException base")
    void badRequestReparented() {
        assertThat(BotManagerException.class)
                .isAssignableFrom(BadRequestException.class);
        assertThat(RuntimeException.class)
                .isAssignableFrom(BadRequestException.class);
    }

    @Test
    @DisplayName("UpstreamGatewayException and its leaves extend the BotManagerException base")
    void upstreamReparented() {
        assertThat(BotManagerException.class)
                .isAssignableFrom(UpstreamGatewayException.class);
        // The concrete leaves inherit the reparenting transitively.
        assertThat(BotManagerException.class)
                .isAssignableFrom(UpstreamLoginException.class);
        assertThat(BotManagerException.class)
                .isAssignableFrom(UpstreamRegistrationException.class);
    }

    @Test
    @DisplayName("A concrete subtype is catchable as the base without losing its message")
    void catchAsBasePreservesMessage() {
        BotManagerException caught = null;
        try {
            throw new BadRequestException("boom");
        } catch (BotManagerException e) {
            caught = e;
        }
        assertThat(caught).isNotNull();
        assertThat(caught).isInstanceOf(BadRequestException.class);
        assertThat(caught.getMessage()).isEqualTo("boom");
    }
}
