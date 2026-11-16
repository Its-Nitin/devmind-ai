package org.springframework.samples.petclinic.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OwnerServiceTests {

    private OwnerService service;

    @BeforeEach
    void setUp() {
        service = new OwnerService();
    }

    @Test
    void findsOwnerById() {
        assertThat(service.findById(1)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void findsEachOwner(int id) {
        assertThat(service.findById(id)).isNotNull();
    }
}
