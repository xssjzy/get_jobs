/**
 * AI 厂商预设。
 *
 * 这里只收录兼容 OpenAI Chat Completions 协议的厂商，后端 AiService 按该协议统一发请求。
 * 想用表里没有的厂商，选「自定义」手填地址和模型名即可，不需要改代码。
 */
export type AiProvider = {
  /** 下拉框里显示的名字 */
  label: string
  /** 写入 BASE_URL 的地址，自定义项为空 */
  baseUrl: string
  /** 写入 MODEL 的默认模型名，自定义项为空 */
  model: string
  /** 申请密钥的页面，展示在输入框下方 */
  docUrl?: string
}

/** 自定义项的标识，不参与地址匹配 */
export const CUSTOM_PROVIDER_ID = '__custom__'

/**
 * 预设列表，键即为下拉框的 value。
 * 顺序决定下拉框顺序，第一项是默认推荐。
 */
export const AI_PROVIDERS: Record<string, AiProvider> = {
  deepseek: {
    label: 'DeepSeek 深度求索',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    docUrl: 'https://platform.deepseek.com/api_keys',
  },
  qwen: {
    label: '通义千问 阿里云',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen-plus',
    docUrl: 'https://bailian.console.aliyun.com/',
  },
  kimi: {
    label: 'Kimi 月之暗面',
    baseUrl: 'https://api.moonshot.cn/v1',
    model: 'moonshot-v1-8k',
    docUrl: 'https://platform.moonshot.cn/console/api-keys',
  },
  glm: {
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    model: 'glm-4-flash',
    docUrl: 'https://open.bigmodel.cn/usercenter/apikeys',
  },
  ark: {
    label: '火山方舟 豆包',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    model: 'doubao-pro-32k',
    docUrl: 'https://console.volcengine.com/ark',
  },
  siliconflow: {
    label: '硅基流动 SiliconFlow',
    baseUrl: 'https://api.siliconflow.cn/v1',
    model: 'deepseek-ai/DeepSeek-V3',
    docUrl: 'https://cloud.siliconflow.cn/account/ak',
  },
  openai: {
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com',
    model: 'gpt-4o-mini',
    docUrl: 'https://platform.openai.com/api-keys',
  },
  [CUSTOM_PROVIDER_ID]: {
    label: '自定义',
    baseUrl: '',
    model: '',
  },
}

/** 配置尚未从后端读回来时，下拉框先显示的厂商 */
export const DEFAULT_PROVIDER_ID = 'deepseek'

/**
 * 归一化地址，用于比较：去首尾空白、统一小写、去掉末尾斜杠。
 */
function normalize(url: string): string {
  return url.trim().toLowerCase().replace(/\/+$/, '')
}

/**
 * 由 BASE_URL 反查当前选中的厂商。
 *
 * 选中项不入库，因为后端的批量保存只认已存在的配置键，新增键会被静默丢弃。
 * 反查规避了这个问题，代价是用户手改地址后下拉会跟着变，这符合直觉。
 *
 * @returns 匹配到的厂商键；地址为空或匹配不上时返回自定义
 */
export function detectProviderId(baseUrl: string | undefined | null): string {
  if (!baseUrl || !baseUrl.trim()) {
    return CUSTOM_PROVIDER_ID
  }
  const target = normalize(baseUrl)
  const hit = Object.entries(AI_PROVIDERS).find(
    ([id, provider]) => id !== CUSTOM_PROVIDER_ID && normalize(provider.baseUrl) === target
  )
  return hit ? hit[0] : CUSTOM_PROVIDER_ID
}
