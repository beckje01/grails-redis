package org.grails.redis

import redis.clients.jedis.Jedis
import redis.clients.jedis.Pipeline
import redis.clients.jedis.Transaction


class RedisService {

    protected static final NO_EXPIRATION_TTL = -1

    def redisPool

    boolean transactional = true

    def withPipeline(Closure closure) {
        withRedis { Jedis redis ->
            Pipeline pipeline = redis.pipelined()
            closure(pipeline)
            pipeline.sync()
        }
    }

    def withTransaction(Closure closure) {
        withRedis { Jedis redis ->
            Transaction transaction = redis.multi()
            closure(transaction)
            transaction.exec()
        }
    }

    def methodMissing(String name, args) {
        withRedis { Jedis redis ->
            redis.invokeMethod(name, args)
        }
    }

    void propertyMissing(String name, Object value) {
        withRedis { Jedis redis ->
            redis.set(name, value.toString())
        }
    }

    Object propertyMissing(String name) {
        withRedis { Jedis redis -> 
            redis.get(name)
        }
    }

    def withRedis(Closure closure) {
        Jedis redis = redisPool.getResource()
        try {
            return closure(redis)
        } finally {
            redisPool.returnResource(redis)
        }
    }


    def flushGroup(String group) {
      withRedis { Jedis redis ->
        def groupkey = "group:"+group
        def keys = redis.smembers(groupkey)
        keys.each{
          redis.del(it)
        }
        redis.del(groupkey)
      }
    }

    def memoize(String key,String group, Closure closure)
    {
      return memoize(key,null,group,closure)
    }

    // SET/GET a value on a Redis key
    def memoize(String key, Integer expire = null,String group = null, Closure closure) {
        withRedis { Jedis redis ->

            //If we are using a group we need to adjust the key used.
            if(group) {
               key=group+"g:"+key
            }


            def result = redis.get(key)
            if (!result) {
                log.debug "cache miss: $key"
                result = closure(redis)
                if (result) {
                    if (!expire) { 
                        redis.set(key, result as String)
                    } else {
                        redis.setex(key, expire, result as String)
                    }
                    //When using a group for a new key we add it to the set for tracking
                    if(group) {
                      redis.sadd("group:"+group,key)
                    }
                }
            } else {
                log.debug "cache hit : $key = $result"
            }
            return result
        }
    }

    def memoizeHash(String key, Integer expire = null, Closure closure) {
        withRedis { Jedis redis ->
            def hash = redis.hgetAll(key)
            if (!hash) {
                log.debug "cache miss: $key"
                hash = closure(redis)
                if (hash) {
                    redis.hmset(key, hash)
                    if (expire) redis.expire(key, expire)
                }
            } else {
                log.debug "cache hit : $key = $hash"
            }
            return hash
        }
    }

    // HSET/HGET a value on a Redis hash at key.field
    // if expire is not null it will be the expire for the whole hash, not this value
    // and will only be set if there isn't already a TTL on the hash
    def memoizeHashField(String key, String field, Integer expire = null, Closure closure) {
        withRedis { Jedis redis ->
            def result = redis.hget(key, field)
            if (!result) {
                log.debug "cache miss: $key.$field"
                result = closure(redis)
                if (result) {
                    redis.hset(key, field, result as String)
                    if (expire && redis.ttl(key) == NO_EXPIRATION_TTL) redis.expire(key, expire)
                }
            } else {
                log.debug "cache hit : $key.$field = $result"
            }
            return result
        }
    }

    // set/get a 'double' score within a sorted set
    // if expire is not null it will be the expire for the whole zset, not this value
    // and will only be set if there isn't already a TTL on the zset
    def memoizeScore(String key, String member, Integer expire = null, Closure closure) {
        withRedis { Jedis redis ->
            def score = redis.zscore(key, member)
            if (!score) {
                log.debug "cache miss: $key.$member"
                score = closure(redis)
                if (score) {
                    redis.zadd(key, score, member)
                    if (expire && redis.ttl(key) == NO_EXPIRATION_TTL) redis.expire(key, expire)
                }
            } else {
                log.debug "cache hit : $key.$member = $score"
            }
            return score
        }
    }

    List memoizeDomainList(Class domainClass, String key, Integer expire = null, Closure closure) {
        List<Long> idList = getIdListFor(key)
        if (idList) return hydrateDomainObjectsFrom(domainClass, idList)

        def domainList = withRedis { Jedis redis ->
            closure(redis)
        }

        saveIdListTo(key, domainList, expire)

        return domainList
    }

    // used when we just want the list of Ids back rather than hydrated objects
    List<Long> memoizeDomainIdList(Class domainClass, String key, Integer expire = null, Closure closure) {
        List<Long> idList = getIdListFor(key)
        if (idList) return idList

        def domainList = withRedis { Jedis redis ->
            closure(redis)
        }

        saveIdListTo(key, domainList, expire)

        return getIdListFor(key)
    }

    protected List<Long> getIdListFor(String key) {
        List<String> idList = withRedis { Jedis redis ->
            redis.lrange(key, 0, -1)
        }

        if (idList) {
            log.debug "$key cache hit, returning ${idList.size()} ids"
            List<Long> idLongList = idList.collect { String id -> id.toLong() }
            return idLongList
        }
    }

    protected void saveIdListTo(String key, List domainList, Integer expire = null) {
        log.debug "$key cache miss, memoizing ${domainList?.size() ?: 0} ids"
        withPipeline { pipeline ->
            for (domain in domainList) {
                pipeline.rpush(key, domain.id as String)
            }
            if (expire) pipeline.expire(key, expire)
        }
    }

    protected List hydrateDomainObjectsFrom(Class domainClass, List<Long> idList) {
        if (domainClass && idList) {
            //return domainClass.findAllByIdInList(idList, [cache: true])
            return idList.collect { id -> domainClass.load(id) }
        }
        return []
    }

    // should ONLY Be used from tests unless we have a really good reason to clear out the entire redis db
    def flushDB() {
        log.warn("flushDB called!")
        withRedis { Jedis redis ->
            redis.flushDB()
        }
    }
}
