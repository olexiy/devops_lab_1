package de.olexiy.devopsplayground.demo.account_service.mapper;

import com.bank.account.model.AccountResponse;
import de.olexiy.devopsplayground.demo.account_service.entity.Account;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountStatus;
import de.olexiy.devopsplayground.demo.account_service.entity.AccountType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(target = "accountType", expression = "java(toTypeEnum(a.getAccountType()))")
    @Mapping(target = "status", expression = "java(toStatusEnum(a.getStatus()))")
    @Mapping(target = "balance", expression = "java(a.getBalance().doubleValue())")
    @Mapping(target = "creditLimit", expression = "java(a.getCreditLimit() != null ? a.getCreditLimit().doubleValue() : null)")
    @Mapping(target = "createdAt", expression = "java(toUtcOffset(a.getCreatedAt()))")
    @Mapping(target = "updatedAt", expression = "java(toUtcOffset(a.getUpdatedAt()))")
    AccountResponse toResponse(Account a);

    default AccountResponse.AccountTypeEnum toTypeEnum(AccountType type) {
        return switch (type) {
            case CHECKING -> AccountResponse.AccountTypeEnum.CHECKING;
            case SAVINGS  -> AccountResponse.AccountTypeEnum.SAVINGS;
            case CREDIT   -> AccountResponse.AccountTypeEnum.CREDIT;
            case DEPOSIT  -> AccountResponse.AccountTypeEnum.DEPOSIT;
        };
    }

    default AccountResponse.StatusEnum toStatusEnum(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> AccountResponse.StatusEnum.ACTIVE;
            case FROZEN -> AccountResponse.StatusEnum.FROZEN;
            case CLOSED -> AccountResponse.StatusEnum.CLOSED;
        };
    }

    default OffsetDateTime toUtcOffset(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
