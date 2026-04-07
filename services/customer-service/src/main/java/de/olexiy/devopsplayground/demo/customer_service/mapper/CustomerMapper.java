package de.olexiy.devopsplayground.demo.customer_service.mapper;

import com.bank.customer.model.CustomerRequest;
import com.bank.customer.model.CustomerResponse;
import de.olexiy.devopsplayground.demo.customer_service.entity.Customer;
import de.olexiy.devopsplayground.demo.customer_service.entity.CustomerStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    @Mapping(target = "status",    expression = "java(toStatusEnum(c.getStatus()))")
    @Mapping(target = "createdAt", expression = "java(toUtcOffset(c.getCreatedAt()))")
    @Mapping(target = "updatedAt", expression = "java(toUtcOffset(c.getUpdatedAt()))")
    CustomerResponse toResponse(Customer c);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "status",    ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Customer toEntity(CustomerRequest req);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "status",    ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateFromRequest(CustomerRequest req, @MappingTarget Customer entity);

    default CustomerResponse.StatusEnum toStatusEnum(CustomerStatus status) {
        return switch (status) {
            case ACTIVE   -> CustomerResponse.StatusEnum.ACTIVE;
            case INACTIVE -> CustomerResponse.StatusEnum.INACTIVE;
            case BLOCKED  -> CustomerResponse.StatusEnum.BLOCKED;
            case CLOSED   -> CustomerResponse.StatusEnum.CLOSED;
        };
    }

    default OffsetDateTime toUtcOffset(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
