package de.olexiy.devopsplayground.demo.customer_service.service;

import com.bank.customer.model.CustomerRequest;
import com.bank.customer.model.CustomerResponse;
import com.bank.customer.model.CustomerStatusRequest;
import com.bank.customer.model.PageCustomerResponse;
import com.bank.customer.model.PageMetadata;
import de.olexiy.devopsplayground.demo.customer_service.entity.CustomerStatus;
import de.olexiy.devopsplayground.demo.customer_service.exception.CustomerEmailConflictException;
import de.olexiy.devopsplayground.demo.customer_service.exception.CustomerNotFoundException;
import de.olexiy.devopsplayground.demo.customer_service.mapper.CustomerMapper;
import de.olexiy.devopsplayground.demo.customer_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
public class CustomerService {

    // Maps API field names to entity field names
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "firstName", "firstName",
            "lastName", "lastName",
            "email", "email",
            "dateOfBirth", "dateOfBirth",
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status"
    );

    private final CustomerRepository repository;
    private final CustomerMapper mapper;

    public CustomerResponse create(CustomerRequest request) {
        if (repository.existsByEmail(request.getEmail())) {
            throw new CustomerEmailConflictException(request.getEmail());
        }
        var customer = mapper.toEntity(request);
        return mapper.toResponse(repository.save(customer));
    }

    @Transactional(readOnly = true)
    public PageCustomerResponse list(Integer page, Integer size, String sort, String status, String lastName) {
        var pageable = buildPageable(page, size, sort);
        CustomerStatus statusEnum = status != null ? CustomerStatus.valueOf(status) : null;
        String lastNameFilter = (lastName != null && lastName.isBlank()) ? null : lastName;

        var result = repository.findByFilters(statusEnum, lastNameFilter, pageable);

        var meta = new PageMetadata()
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast());

        return new PageCustomerResponse()
                .content(result.getContent().stream().map(mapper::toResponse).toList())
                .page(meta);
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public CustomerResponse findByEmail(String email) {
        return repository.findByEmail(email)
                .map(mapper::toResponse)
                .orElseThrow(() -> new CustomerNotFoundException(email));
    }

    public CustomerResponse update(Long id, CustomerRequest request) {
        var customer = repository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        repository.findByEmailAndIdNot(request.getEmail(), id)
                .ifPresent(existing -> { throw new CustomerEmailConflictException(request.getEmail()); });

        mapper.updateFromRequest(request, customer);
        return mapper.toResponse(repository.save(customer));
    }

    public CustomerResponse updateStatus(Long id, CustomerStatusRequest statusRequest) {
        var customer = repository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        var newStatus = CustomerStatus.valueOf(statusRequest.getStatus().getValue());
        customer.setStatus(newStatus);
        return mapper.toResponse(repository.save(customer));
    }

    public void delete(Long id) {
        var customer = repository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customer.setStatus(CustomerStatus.CLOSED);
        repository.save(customer);
    }

    private PageRequest buildPageable(Integer page, Integer size, String sort) {
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        String[] parts = sort.split(",", 2);
        String field = SORT_FIELDS.getOrDefault(parts[0].trim(), "createdAt");
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, field));
    }
}
