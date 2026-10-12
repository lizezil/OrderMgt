package com.example.ordermgt.assistant.infrastructure.persist;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ordermgt.assistant.domain.Conversation;
import com.example.ordermgt.assistant.domain.ConversationRepository;
import com.example.ordermgt.assistant.domain.Message;
import com.example.ordermgt.assistant.domain.Role;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepositoryImpl implements ConversationRepository {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationRepositoryImpl(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public Conversation save(Conversation conversation) {
        ConversationPO po = new ConversationPO();
        po.setId(conversation.getId());
        po.setCustomerId(conversation.getCustomerId());
        po.setTitle(conversation.getTitle());
        po.setStatus("ACTIVE");
        po.setCreatedAt(conversation.getCreatedAt());
        po.setUpdatedAt(conversation.getUpdatedAt());
        po.setLastMessageAt(conversation.getLastMessageAt());

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
        return Optional.ofNullable(conversationMapper.selectById(id)).map(this::toDomain);
    }

    @Override
    public List<Conversation> findByCustomer(Long customerId) {
        return conversationMapper.selectList(Wrappers.<ConversationPO>lambdaQuery()
                        .eq(ConversationPO::getCustomerId, customerId)
                        .orderByDesc(ConversationPO::getLastMessageAt)
                        .orderByDesc(ConversationPO::getId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Message append(Message message) {
        MessagePO po = new MessagePO();
        po.setConversationId(message.conversationId());
        po.setRole(message.role().name());
        po.setContent(message.content());
        po.setPendingActionId(message.pendingActionId());
        po.setCreatedAt(message.createdAt());
        messageMapper.insert(po);
        return new Message(po.getId(), message.conversationId(), message.role(),
                message.content(), message.pendingActionId(), message.createdAt());
    }

    @Override
    public List<Message> findRecentMessages(Long conversationId, int limit) {
        int safeLimit = Math.max(limit, 1);
        List<MessagePO> newestFirst = messageMapper.selectList(Wrappers.<MessagePO>lambdaQuery()
                .eq(MessagePO::getConversationId, conversationId)
                .orderByDesc(MessagePO::getId)
                .last("LIMIT " + safeLimit));
        List<Message> ordered = new ArrayList<>(newestFirst.stream().map(this::toDomain).toList());
        Collections.reverse(ordered);
        return ordered;
    }

    @Override
    public List<Message> findAllMessages(Long conversationId) {
        return messageMapper.selectList(Wrappers.<MessagePO>lambdaQuery()
                        .eq(MessagePO::getConversationId, conversationId)
                        .orderByAsc(MessagePO::getId))
                .stream().map(this::toDomain).toList();
    }

    private Conversation toDomain(ConversationPO po) {
        return Conversation.rehydrate(po.getId(), po.getCustomerId(), po.getTitle(),
                po.getCreatedAt(), po.getUpdatedAt(), po.getLastMessageAt());
    }

    private Message toDomain(MessagePO po) {
        return new Message(po.getId(), po.getConversationId(), Role.valueOf(po.getRole()),
                po.getContent(), po.getPendingActionId(), po.getCreatedAt());
    }
}
