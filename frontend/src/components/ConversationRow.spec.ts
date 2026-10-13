import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Conversation } from '../types'
import ConversationRow from './ConversationRow.vue'

function conversation(status: Conversation['status'] = 'ACTIVE'): Conversation {
  const at = '2026-01-01T00:00:00'
  return {
    id: 7,
    title: '我有哪些订单？',
    createdAt: at,
    updatedAt: at,
    lastMessageAt: at,
    status,
    state: 'IDLE',
    runId: null,
  }
}

/**
 * The sidebar row and its overflow menu: the only place a conversation's lifecycle is changed, and
 * deliberately without a delete entry -- archiving hides, it never destroys (ADR-0013).
 */
describe('会话行', () => {
  it('菜单默认收起，点开后只有一条动作', async () => {
    const wrapper = mount(ConversationRow, {
      props: { conversation: conversation(), active: false },
    })

    expect(wrapper.find('.menu-item').exists()).toBe(false)

    await wrapper.get('.entry-more').trigger('click')

    expect(wrapper.findAll('.menu-item').map((item) => item.text())).toEqual(['归档'])
    // No hard delete is offered anywhere.
    expect(wrapper.text()).not.toContain('删除')
  })

  it('归档的行走「恢复」', async () => {
    const wrapper = mount(ConversationRow, {
      props: { conversation: conversation('ARCHIVED'), active: false },
    })

    await wrapper.get('.entry-more').trigger('click')

    expect(wrapper.get('.menu-item').text()).toBe('恢复')
  })

  it('选一个动作就 emit 出去并收起菜单，且不会顺带打开会话', async () => {
    const wrapper = mount(ConversationRow, {
      props: { conversation: conversation(), active: false },
    })

    await wrapper.get('.entry-more').trigger('click')
    await wrapper.get('.menu-item').trigger('click')

    expect(wrapper.emitted('archive')).toHaveLength(1)
    expect(wrapper.emitted('open')).toBeUndefined()
    expect(wrapper.find('.menu-item').exists()).toBe(false)
  })

  it('点行本身是打开会话', async () => {
    const wrapper = mount(ConversationRow, {
      props: { conversation: conversation(), active: true },
    })

    await wrapper.get('.entry').trigger('click')

    expect(wrapper.emitted('open')).toHaveLength(1)
    expect(wrapper.get('.entry').classes()).toContain('active')
  })
})
