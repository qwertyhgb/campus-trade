export const productStatusText: Record<number, string> = { 0: '已下架', 1: '在售', 2: '已锁定', 3: '已售出', 4: '待审核', 5: '已驳回' }
export const activityStatusText: Record<number, string> = { 0: '草稿', 1: '待审核', 2: '审核拒绝', 3: '报名中', 4: '报名结束', 5: '进行中', 6: '已结束', 7: '已下架' }
export const conditionLevelText: Record<number, string> = { 0: '全新', 1: '几乎全新', 2: '轻微使用', 3: '明显使用' }
export const orderStatusText: Record<number, string> = { 0: '待确认', 1: '已完成', 2: '已取消' }
export function getStatusText(dictionary: Record<number, string>, status: number) { return dictionary[status] ?? '未知状态' }

/** 公开活动状态（3 报名中 / 4 报名结束 / 5 进行中 / 6 已结束）：后端仅允许普通用户按这些状态筛选，传其他状态会报 400。 */
export const publicActivityStatusOptions = [
  { value: 3, label: '报名中' },
  { value: 4, label: '报名结束' },
  { value: 5, label: '进行中' },
  { value: 6, label: '已结束' },
]
