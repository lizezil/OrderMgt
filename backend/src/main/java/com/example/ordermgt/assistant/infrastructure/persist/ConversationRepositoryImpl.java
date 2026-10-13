package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class ConversationRepositoryImpl implements ConversationRepository {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationPersistenceMapper persistenceMapper;

    @Override
    public Conversation save(Conversation conversation) {
        ConversationPO po = persistenceMapper.toPo(conversation);
        if (conversation.getId() == null) {
            conversationMapper.insert(po);
            conversation.assignId(po.getId());
        } else {
            conversationMapper.updateById(po);
        }
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(Long id) {
        return Optional.ofNullable(conversationMapper.selectById(id))
                .map(persistenceMapper::toDomain);
    }

    @Override
    public List<Conversation> findByCustomer(Long customerId) {
        return conversationMapper
                .selectList(
                        Wrappers.<ConversationPO>lambdaQuery()
                                .eq(ConversationPO::getCustomerId, customerId)
                                .orderByDesc(ConversationPO::getLastMessageAt)
                                .orderByDesc(ConversationPO::getId))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }

    @Override
    public Message append(Message message) {
        MessagePO po = persistenceMapper.toPo(message);
        messageMapper.insert(po);
        return persistenceMapper.toDomain(po);
    }

    @Override
    public List<Message> findRecentMessages(Long conversationId, int limit) {
        int safeLimit = Math.max(limit, 1);
        List<MessagePO> newestFirst =
                messageMapper.selectList(
                        Wrappers.<MessagePO>lambdaQuery()
                                .eq(MessagePO::getConversationId, conversationId)
                                .orderByDesc(MessagePO::getId)
                                .last("LIMIT " + safeLimit));
        List<Message> ordered =
                new ArrayList<>(newestFirst.stream().map(persistenceMapper::toDomain).toList());
        Collections.reverse(ordered);
        return ordered;
    }

    @Override
    public List<Message> findAllMessages(Long conversationId) {
        return messageMapper
                .selectList(
                        Wrappers.<MessagePO>lambdaQuery()
                                .eq(MessagePO::getConversationId, conversationId)
                                .orderByAsc(MessagePO::getId))
                .stream()
                .map(persistenceMapper::toDomain)
                .toList();
    }
}
