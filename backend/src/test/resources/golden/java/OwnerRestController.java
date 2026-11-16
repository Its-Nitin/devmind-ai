package org.springframework.samples.petclinic.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owners over REST.
 */
@RestController
@RequestMapping("/api")
public class OwnerRestController {

    private final OwnerService ownerService;

    public OwnerRestController(OwnerService ownerService) {
        this.ownerService = ownerService;
    }

    /** Finds one owner. */
    @GetMapping(
            value = "/owners/{ownerId}",
            produces = "application/json")
    public ResponseEntity<OwnerDto> getOwner(@PathVariable("ownerId") Integer ownerId) {
        return ResponseEntity.ok(ownerService.findById(ownerId));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/owners/{ownerId}")
    public void deleteOwner(@PathVariable int ownerId) {
        ownerService.delete(ownerId);
    }
}
