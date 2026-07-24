-- KEYS[1] = stock counter key
-- KEYS[2] = lock hash key
-- ARGV[1] = action ("confirm" or "cancel")
-- ARGV[2] = orderId
-- ARGV[3] = quantity to release / confirm
--
-- action=confirm: 物理扣减锁定量（数量不再回填），locked - qty
-- action=cancel:  把锁定数量归还到可用库存，stock += qty, locked -= qty
-- 返回: { status, currentStock, lockedRemain }

local stockKey = KEYS[1]
local lockKey  = KEYS[2]
local action   = ARGV[1]
local orderId  = ARGV[2]
local qty      = tonumber(ARGV[3])

if qty == nil or qty <= 0 then
    return {-1, 0, 0}
end

local locked = tonumber(redis.call('HGET', lockKey, orderId) or '0')
local current = tonumber(redis.call('GET', stockKey) or '0')

if locked < qty then
    return {0, current, locked}
end

if action == 'confirm' then
    redis.call('HDEL', lockKey, orderId)
    return {1, current, locked - qty}
elseif action == 'cancel' then
    redis.call('INCRBY', stockKey, qty)
    redis.call('HDEL', lockKey, orderId)
    return {1, current + qty, locked - qty}
else
    return {-1, current, locked}
end