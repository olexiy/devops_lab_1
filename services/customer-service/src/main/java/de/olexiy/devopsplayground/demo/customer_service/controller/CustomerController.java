package de.olexiy.devopsplayground.demo.customer_service.controller;

import com.bank.customer.api.ApiApi;
import com.bank.customer.model.CustomerRequest;
import com.bank.customer.model.CustomerResponse;
import com.bank.customer.model.CustomerStatusRequest;
import com.bank.customer.model.PageCustomerResponse;
import de.olexiy.devopsplayground.demo.customer_service.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class CustomerController implements ApiApi {

    private final CustomerService service;

    @Override
    public ResponseEntity<CustomerResponse> createCustomer(CustomerRequest customerRequest) {
        var created = service.create(customerRequest);
        return ResponseEntity
                .created(URI.create("/api/v1/customers/" + created.getId()))
                .body(created);
    }

    @Override
    public ResponseEntity<PageCustomerResponse> listCustomers(
            Integer page, Integer size, String sort, String status, String lastName) {
        return ResponseEntity.ok(service.list(page, size, sort, status, lastName));
    }

    @Override
    public ResponseEntity<CustomerResponse> getCustomerById(Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @Override
    public ResponseEntity<CustomerResponse> getCustomerByEmail(String email) {
        return ResponseEntity.ok(service.findByEmail(email));
    }

    @Override
    public ResponseEntity<CustomerResponse> updateCustomer(Long id, CustomerRequest customerRequest) {
        return ResponseEntity.ok(service.update(id, customerRequest));
    }

    @Override
    public ResponseEntity<CustomerResponse> updateCustomerStatus(Long id, CustomerStatusRequest customerStatusRequest) {
        return ResponseEntity.ok(service.updateStatus(id, customerStatusRequest));
    }

    @Override
    public ResponseEntity<Void> deleteCustomer(Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
