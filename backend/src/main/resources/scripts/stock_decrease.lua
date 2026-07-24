-- KEYS[1] = stock counter key (e.g. stock:available:{skuId})
-- KEYS[2] = lock hash key       (e.g. stock:lock:{skuId})
-- ARGV[1] = quantity (int)
-- ARGV[2] = orderId / sourceNo
-- ARGV[3] = ttl seconds
--
-- 返回: { status, remaining, lockedTotal }
-- status: 1=扣减成功, 0=库存不足, -1=参数错误

local stockKey = KEYS[1]
local lockKey  = KEYS[2]
local qty      = tonumber(ARGV[1])
local orderId  = ARGV[2]
local ttl      = tonumber(ARGV[3])

if qty == nil or qty <= 0 then
    return {-1, 0, 0}
end

local current = tonumber(redis.call('GET', stockKey) or '0')
if current < qty then
    return {0, current, tonumber(redis.call('HGET', lockKey, orderId) or '0')}
end

redis.call('DECRBY', stockKey, qty)
redis.call('HSET', lockKey, orderId, qty)
if ttl and ttl > 0 then
    redis.call('EXPIRE', lockKey, ttl)
end
return {1, current - qty, qty}