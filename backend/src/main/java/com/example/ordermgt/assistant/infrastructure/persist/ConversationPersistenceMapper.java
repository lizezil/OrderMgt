package com.example.ordermgt.assistant.infrastructure.persist;

import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationStatus;
import com.example.ordermgt.assistant.domain.Message;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * Rows and domain types for conversations and messages.
 *
 * <p>{@link Message} is a record, so MapStruct builds it directly. {@link Conversation} keeps its
 * constructor private and can only be rebuilt through its {@code rehydrate} factory, so that one
 * direction is a default method.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ConversationPersistenceMapper {

    ConversationPO toPo(Conversation conversation);

    MessagePO toPo(Message message);

    Message toDomain(MessagePO po);

    default Conversation toDomain(ConversationPO po) {
        return Conversation.rehydrate(
                po.getId(),
                po.getCustomerId(),
                po.getTitle(),
                ConversationStatus.valueOf(po.getStatus()),
                po.getCreatedAt(),
                po.getUpdatedAt(),
                po.getLastMessageAt());
    }
}
