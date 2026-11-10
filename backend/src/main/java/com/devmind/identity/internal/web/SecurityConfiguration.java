package com.devmind.identity.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Session auth for the same-origin SPA (ADR-0010, SEC-7). Login is a JSON endpoint, so form login and HTTP Basic are
 * off. The XSRF-TOKEN cookie is readable by JavaScript and must come back as the {@code X-XSRF-TOKEN} header.
 * Unauthenticated and forbidden requests get the same ProblemDetails as the rest of the API.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String LOGIN_PATH = "/api/auth/login";
    static final String LOGOUT_PATH = "/api/auth/logout";
    static final String CSRF_PATH = "/api/auth/csrf";

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        ProblemDetailResponder problems = new ProblemDetailResponder(exceptionResolver);
        http.authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, LOGIN_PATH, LOGOUT_PATH)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, CSRF_PATH)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // spa() sets the cookie repository and the SPA token handler (raw or masked tokens). Our own
                // repository replaces its default so the login code rotates the same cookie, with SameSite set.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(problems::respond).accessDeniedHandler(problems::respond))
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterAfter(new UserIdMdcFilter(), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** bcrypt by default; the {@code {id}} prefix lets hashes migrate to a newer algorithm later (SEC-7). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    /** The default repository pair, shared by the filter chain and {@link SessionLogin}. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    /**
     * What the filter chain would do after a filter-based login: a new session ID (fixation protection) and a new
     * CSRF token. The JSON login runs in a controller, so {@link SessionLogin} applies it explicitly.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /** Hands security exceptions to the MVC exception resolvers, so the global advice renders the ProblemDetail. */
    private record ProblemDetailResponder(HandlerExceptionResolver resolver) {

        void respond(HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
            if (resolver.resolveException(request, response, null, ex) == null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }
}
