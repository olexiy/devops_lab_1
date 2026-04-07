package de.olexiy.devopsplayground.demo.transaction_service.mapper;

import com.bank.transaction.model.TransactionResponse;
import de.olexiy.devopsplayground.demo.transaction_service.entity.Transaction;
import de.olexiy.devopsplayground.demo.transaction_service.entity.TransactionType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "transactionType", expression = "java(toTypeEnum(t.getTransactionType()))")
    @Mapping(target = "amount", expression = "java(t.getAmount().doubleValue())")
    @Mapping(target = "balanceAfter", expression = "java(t.getBalanceAfter().doubleValue())")
    @Mapping(target = "transactionDate", expression = "java(toUtcOffset(t.getTransactionDate()))")
    @Mapping(target = "createdAt", expression = "java(toUtcOffset(t.getCreatedAt()))")
    TransactionResponse toResponse(Transaction t);

    default TransactionResponse.TransactionTypeEnum toTypeEnum(TransactionType type) {
        return switch (type) {
            case CREDIT       -> TransactionResponse.TransactionTypeEnum.CREDIT;
            case DEBIT        -> TransactionResponse.TransactionTypeEnum.DEBIT;
            case TRANSFER_IN  -> TransactionResponse.TransactionTypeEnum.TRANSFER_IN;
            case TRANSFER_OUT -> TransactionResponse.TransactionTypeEnum.TRANSFER_OUT;
            case FEE          -> TransactionResponse.TransactionTypeEnum.FEE;
        };
    }

    default OffsetDateTime toUtcOffset(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
