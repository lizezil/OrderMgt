import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { Order, PageResult } from '../types'
import OrdersView from './OrdersView.vue'

vi.mock('../api', () => ({ api: { listOrders: vi.fn() } }))

const listOrdersMock = vi.mocked(api.listOrders)

function order(overrides: Partial<Order> = {}): Order {
  return {
    orderNo: 'ORD20260101120000000000',
    status: 'CREATED',
    totalAmount: 99,
    lines: [{ sku: 'GADGET-02', itemName: 'Gadget', quantity: 2, unitPrice: 49.5, lineAmount: 99 }],
    createdAt: '2026-01-01T12:00:00',
    updatedAt: '2026-01-01T12:00:00',
    ...overrides,
  }
}

function page(overrides: Partial<PageResult<Order>> = {}): PageResult<Order> {
  return { items: [order()], total: 1, page: 1, size: 20, ...overrides }
}

async function view() {
  const wrapper = mount(OrdersView)
  await flushPromises()
  return wrapper
}

// Each test states its own response: `restoreMocks` clears implementations between tests, and a
// manual `mockReset()` here would additionally make Vitest report handled rejections as unhandled.
describe('订单列表', () => {
  it('只显示当前客户的订单，状态各有标记、金额保留两位', async () => {
    listOrdersMock.mockResolvedValue(
      page({
        items: [
          order({ orderNo: 'ORD-A', status: 'CREATED' }),
          order({ orderNo: 'ORD-B', status: 'COMPLETED' }),
          order({ orderNo: 'ORD-C', status: 'CANCELLED' }),
        ],
        total: 3,
      }),
    )

    const wrapper = await view()

    expect(listOrdersMock).toHaveBeenCalledWith(1, 20)
    expect(wrapper.findAll('tbody .mark').map((mark) => mark.classes())).toEqual([
      ['mark'],
      ['mark', 'done'],
      ['mark', 'refused'],
    ])
    expect(wrapper.get('tbody .num').text()).toBe('¥99.00')
  })

  it('点开一行才展开明细，单价与数量都对得上', async () => {
    listOrdersMock.mockResolvedValue(page())

    const wrapper = await view()

    expect(wrapper.find('.docket-open').exists()).toBe(false)

    await wrapper.get('tbody tr').trigger('click')

    const detail = wrapper.get('.docket-open')
    expect(detail.text()).toContain('GADGET-02')
    expect(detail.text()).toContain('Gadget')
    expect(detail.text()).toContain('× 2')
    expect(detail.text()).toContain('49.50')
  })

  it('没有订单时给出去哪儿下单的指引，而不是一张空表', async () => {
    listOrdersMock.mockResolvedValue(page({ items: [], total: 0 }))

    const wrapper = await view()

    expect(wrapper.text()).toContain('这个客户还没有订单')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('加载失败时说出原因，不冒充成「没有订单」', async () => {
    listOrdersMock.mockRejectedValue(new Error('无权访问该订单'))

    const wrapper = await view()

    expect(wrapper.text()).toContain('无权访问该订单')
    expect(wrapper.text()).not.toContain('这个客户还没有订单')
  })
})

describe('翻页', () => {
  it('第一页没有上一页，翻到末页后没有下一页', async () => {
    listOrdersMock.mockResolvedValue(page({ total: 25 }))
    const wrapper = await view()
    const buttons = () => wrapper.findAll('.pager button')

    expect(wrapper.get('.pager .data').text()).toBe('1 / 2 · 共 25 单')
    expect(buttons()[0]!.attributes('disabled')).toBeDefined()
    expect(buttons()[1]!.attributes('disabled')).toBeUndefined()

    listOrdersMock.mockResolvedValue(page({ page: 2, total: 25 }))
    await buttons()[1]!.trigger('click')
    await flushPromises()

    expect(listOrdersMock).toHaveBeenLastCalledWith(2, 20)
    expect(buttons()[0]!.attributes('disabled')).toBeUndefined()
    expect(buttons()[1]!.attributes('disabled')).toBeDefined()
  })

  it('翻页失败时页码留在原地：页码与账目不脱节，重试从这一页再翻', async () => {
    listOrdersMock.mockResolvedValueOnce(page({ total: 25 }))
    const wrapper = await view()
    const next = () => wrapper.findAll('.pager button')[1]!

    listOrdersMock.mockRejectedValueOnce(new Error('网络中断'))
    await next().trigger('click')
    await flushPromises()

    // 没翻成就没换页：页码必须还指着屏幕上这份账目。
    expect(wrapper.get('.pager .data').text()).toBe('1 / 2 · 共 25 单')
    expect(wrapper.text()).toContain('网络中断')

    listOrdersMock.mockResolvedValueOnce(page({ page: 2, total: 25 }))
    await next().trigger('click')
    await flushPromises()

    expect(wrapper.get('.pager .data').text()).toBe('2 / 2 · 共 25 单')
  })
})

describe('加载中', () => {
  it('首屏还没回来时不冒充「没有订单」', async () => {
    listOrdersMock.mockReturnValue(new Promise(() => {}))

    const wrapper = mount(OrdersView)
    await flushPromises()

    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.text()).not.toContain('这个客户还没有订单')
  })
})
