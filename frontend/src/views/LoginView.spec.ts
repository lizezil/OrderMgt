import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, login } from '../api'
import LoginView from './LoginView.vue'

vi.mock('../api', () => ({
  login: vi.fn(),
  ApiError: class extends Error {
    code: string
    status: number
    constructor(code: string, message: string, status: number) {
      super(message)
      this.code = code
      this.status = status
    }
  },
}))

const loginMock = vi.mocked(login)

/** The PIN gate: two fields, one submit, and a wrong pair stays on this page with a sayable error. */
describe('登录页', () => {
  it('没填完不提交 —— 空表单给出提示而不是打一个注定 401 的请求', async () => {
    const wrapper = mount(LoginView)

    await wrapper.find('form').trigger('submit')

    expect(loginMock).not.toHaveBeenCalled()
    expect(wrapper.find('.login-error').text()).toBe('请填写客户编号和 PIN')
  })

  it('填好后提交，成功即把会话里的客户号报给外壳', async () => {
    loginMock.mockResolvedValue('1001')
    const wrapper = mount(LoginView)

    await wrapper.find('[data-testid=customer]').setValue(' 1001 ')
    await wrapper.find('[data-testid=pin]').setValue('1111')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(loginMock).toHaveBeenCalledWith('1001', '1111')
    expect(wrapper.emitted('login')?.[0]).toEqual(['1001'])
  })

  it('401 是客户号或 PIN 错 —— 停在本页用人话提示', async () => {
    loginMock.mockRejectedValue(
      new ApiError('UNAUTHORIZED', 'customer id or PIN is incorrect', 401),
    )
    const wrapper = mount(LoginView)

    await wrapper.find('[data-testid=customer]').setValue('1001')
    await wrapper.find('[data-testid=pin]').setValue('0000')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.login-error').text()).toBe('客户编号或 PIN 不正确')
    expect(wrapper.emitted('login')).toBeUndefined()
  })

  it('其余失败（网络、500）显示通用提示，不透出框架原文', async () => {
    loginMock.mockRejectedValue(new Error('connect ECONNREFUSED'))
    const wrapper = mount(LoginView)

    await wrapper.find('[data-testid=customer]').setValue('1001')
    await wrapper.find('[data-testid=pin]').setValue('1111')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.login-error').text()).toBe('登录失败，请重试')
  })
})
