import { aiRecommendStream } from '@/api/ai'
import { useUserStore } from '@/stores/user'

/**
 * AI 可用性探测去重（N5 收口）
 * 问题背景：StudentLayout 挂载 AiAssistant 时 onMounted 调真实 aiRecommend，ClassroomList 的
 * onMounted 又调一次 → AI 开启时进入「布局 + 教室列表」会发 2 次 /api/ai/recommend
 * （该接口开启时会真实调用大模型）。
 *
 * 方案：统一经 probeAiRecommend 探测，按 userId 分键缓存（Map<userId, { promise, result }>）：
 *   - 缓存 in-flight promise 以合并并发请求（布局与列表同时挂载只发一次）；
 *   - 成功 / 失败（异常、enabled=false）均缓存结果，避免重复打接口；
 *   - force=true 绕过缓存（当前无 UI 刷新入口，留作后续使用）；
 *   - 硬要求：缓存必须按 userId 隔离，否则切换账号会拿到上一个用户的推荐。
 *
 * 调用口径（与 L13 一致）：probe 内部不发送 userId——
 * 后端 AiController 一律取 UserContext 当前登录用户，忽略请求体。
 * R13-5：推荐接口已 SSE 结构化流式化，probe 内部走 ?stream=true，完整消费
 * meta → phase（缓存未命中时 1 个，onPhase 回调可用于进度提示）→ result（同构 VO）→ done，
 * 落地结果与旧非流式 JSON 同构，调用方无感知。
 *
 * R8：登录接口已随 LoginVO 下发 aiEnabled，并落盘 localStorage（刷新后仍生效）。
 * 后端明确关闭（false）时直接返回未启用，不再打一次 /ai/recommend 才知道要隐藏入口；
 * 开关为 true / 未知（null）时仍需调用，因为推荐内容只能由该接口返回。
 */

/** 探测结果缓存：userId → { promise, result }（result 用于结构完整，调用方统一 await promise 取值） */
const probeCache = new Map()

/** 未启用时的统一返回值（不含推荐内容） */
function disabledResult() {
  return { enabled: false, recommendations: [], message: '' }
}

/**
 * 探测 AI 可用性（含推荐内容）。返回 Promise<{ enabled: boolean, recommendations: Array, message: string }>；
 * 未登录 / 后端开关关闭 / 探测失败一律返回 enabled=false（组件据此隐藏 AI 入口，不阻断页面）。
 *
 * message：后端降级提示（限流/密钥缺失/服务异常/输出格式异常时非空），
 * 由调用方透出到界面，使用户能区分「真实模型结果」与「本地规则降级结果」。
 *
 * @param {{ force?: boolean, onPhase?: Function }} options
 *        force=true 绕过缓存强制发起新调用；
 *        onPhase({phase,text}) 可选进度回调（仅缓存未命中、真正调用模型前触发一次）
 */
export function probeAiRecommend({ force = false, onPhase } = {}) {
  const userStore = useUserStore()
  const userId = userStore.userInfo ? userStore.userInfo.id : null
  if (userId == null) {
    // 未登录：无用户维度可缓存，直接返回未启用（与既有行为一致：组件静默隐藏）
    return Promise.resolve(disabledResult())
  }
  if (userStore.aiEnabled === false) {
    // R8：登录时后端已明确关闭 AI，直接短路，省掉这次 /ai/recommend 调用
    return Promise.resolve(disabledResult())
  }
  if (!force) {
    const hit = probeCache.get(userId)
    if (hit) {
      return hit.promise
    }
  }
  const promise = new Promise(resolve => {
    let settled = false
    // result/done/error 三个终态只落地一次（done 无载荷，以已收到的 result 为准；
    // 未收到 result 即 done/error，按未启用兜底）
    let holder = null
    const finish = result => {
      if (settled) {
        return
      }
      settled = true
      holder = result
      probeCache.set(userId, { promise, result })
      resolve(result)
    }
    // R13-5：流式消费，result 帧 VO 与旧非流式 res.data 完全同构
    aiRecommendStream({
      onPhase: d => onPhase && onPhase(d),
      onResult: data => {
        const vo = data || {}
        finish({
          enabled: vo.enabled === true,
          recommendations: vo.recommendations || [],
          // 降级提示透传（AI 关闭时 message 为「AI 服务未启用…」，调用方按 enabled 区分处理）
          message: vo.enabled === true ? vo.message || '' : ''
        })
      },
      onDone: () => finish(holder || disabledResult()),
      // 连接级失败（超时/线程池繁忙/网络异常）也缓存 enabled=false，避免重复打接口
      onError: () => finish(disabledResult())
    })
  })
  // 先同步占位（缓存 in-flight promise），合并并发请求
  probeCache.set(userId, { promise, result: null })
  return promise
}

/**
 * 清理探测缓存：不传 userId 清空全部（登出用）；传 userId 只清该账号缓存。
 */
export function clearAiProbe(userId) {
  if (userId === undefined || userId === null) {
    probeCache.clear()
  } else {
    probeCache.delete(userId)
  }
}
