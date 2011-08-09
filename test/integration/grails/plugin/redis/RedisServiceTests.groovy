package grails.plugin.redis

import static grails.plugin.redis.RedisService.NO_EXPIRATION_TTL
import redis.clients.jedis.Jedis
import redis.clients.jedis.Transaction

class RedisServiceTests extends GroovyTestCase {
    def redisService

    protected void setUp() {
        super.setUp()
        redisService.flushDB()
    }

    void testFlushDB() {
        // actually called as part of setup too, but we can test it here
        redisService.withRedis { Jedis redis ->
            assertEquals 0, redis.dbSize()
            redis.set("foo", "bar")
            assertEquals 1, redis.dbSize()
        }

        redisService.flushDB()

        redisService.withRedis { Jedis redis ->
            assertEquals 0, redis.dbSize()
        }
    }

    void testMemoizeKey() {
        def calledCount = 0
        def cacheMissClosure = {
            calledCount += 1
            return "foo"
        }
        def cacheMissResult = redisService.memoize("mykey", cacheMissClosure)

        assertEquals 1, calledCount
        assertEquals "foo", cacheMissResult
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")

        def cacheHitResult = redisService.memoize("mykey", cacheMissClosure)

        // should have hit the cache, not called our method again
        assertEquals 1, calledCount
        assertEquals "foo", cacheHitResult
    }

    def testMemoizeGroup()
    {
        def calledCount = 0
        def cacheMissClosure = {
            calledCount += 1
            return "foo"
        }

        def cacheMissResult = redisService.memoize("key",[group:"group"],cacheMissClosure)

        assertEquals 1, calledCount
        assertEquals "foo", cacheMissResult
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("groupg:key")
        assertEquals "foo", redisService.get("groupg:key")


        def cacheHitResult = redisService.memoize("key",[group:"group"], cacheMissClosure)
        assertEquals 1, calledCount
        assertEquals "foo", cacheHitResult
    }

    def testFlushGroup()
    {
        def calledCount = 0
        def cacheClosure = {
            calledCount += 1
            return "foo"
        }

        def cacheMissResult = redisService.memoize("key",[group:"group"],cacheClosure)

        assertEquals 1, calledCount
        assertEquals "foo", cacheMissResult
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("groupg:key")
        assertEquals "foo", redisService.get("groupg:key")

        redisService.flushGroup("group")

        def cacheResult = redisService.memoize("key",[group:"group"], cacheClosure)
        assertEquals 2, calledCount
        assertEquals "foo", cacheResult
    }

    def testFlushGroupWithMultiKeys()
    {
        def calledCount = 0
        def cacheClosure = {
            calledCount += 1
            return "foo"
        }

        def calledCountBob = 0
        def cacheClosureBob = {
            calledCountBob += 1
            return "Bob Dole"
        }

        def foores = redisService.memoize("fooKey",[group:"group"],cacheClosure)
        def bobres = redisService.memoize("bobKey",[group:"group"],cacheClosureBob)

        assertEquals "foo", foores
        assertEquals 1, calledCount

        assertEquals "Bob Dole",bobres
        assertEquals 1, calledCountBob

        bobres = redisService.memoize("bobKey",[group:"group"],cacheClosureBob)
        
        assertEquals "Bob Dole",bobres
        assertEquals 1, calledCountBob

        redisService.flushGroup("group")

        assertNull  redisService.get("groupg:fooKey")
        assertNull  redisService.get("groupg:bobKey")

        bobres = redisService.memoize("bobKey",[group:"group"],cacheClosureBob)

        assertEquals "Bob Dole",bobres
        assertEquals 2, calledCountBob
    }

    
    void testMemoizeKeyWithExpire() {
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")
        def result = redisService.memoize("mykey", 60) { "foo" }
        assertEquals "foo", result
        assertTrue NO_EXPIRATION_TTL < redisService.ttl("mykey")
    }

    void testMemoizeHashField() {
        def calledCount = 0
        def cacheMissClosure = {
            calledCount += 1
            return "foo"
        }
        def cacheMissResult = redisService.memoizeHashField("mykey", "first", cacheMissClosure)

        assertEquals 1, calledCount
        assertEquals "foo", cacheMissResult
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")

        def cacheHitResult = redisService.memoizeHashField("mykey", "first", cacheMissClosure)

        // should have hit the cache, not called our method again
        assertEquals 1, calledCount
        assertEquals "foo", cacheHitResult

        def cacheMissSecondResult = redisService.memoizeHashField("mykey", "second", cacheMissClosure)

        // cache miss because we're using a different field in the same key
        assertEquals 2, calledCount
        assertEquals "foo", cacheMissSecondResult
    }

    void testMemoizeHashFieldWithExpire() {
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")
        def result = redisService.memoizeHashField("mykey", "first", 60) { "foo" }
        assertEquals "foo", result
        assertTrue NO_EXPIRATION_TTL < redisService.ttl("mykey")
    }

    def testMemoizeHash() {
        def calledCount = 0
        def expectedHash = [foo: 'bar', baz: 'qux']
        def cacheMissClosure = {
            calledCount += 1
            return expectedHash
        }
        def cacheMissResult = redisService.memoizeHash("mykey", cacheMissClosure)

        assertEquals 1, calledCount
        assertEquals expectedHash, cacheMissResult
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")

        def cacheHitResult = redisService.memoizeHash("mykey", cacheMissClosure)

        // should have hit the cache, not called our method again
        assertEquals 1, calledCount
        assertEquals expectedHash, cacheHitResult
    }

    void testMemoizeHashWithExpire() {
        def expectedHash = [foo: 'bar', baz: 'qux']
        assertEquals NO_EXPIRATION_TTL, redisService.ttl("mykey")
        def result = redisService.memoizeHash("mykey", 60) { expectedHash }
        assertEquals expectedHash, result
        assertTrue NO_EXPIRATION_TTL < redisService.ttl("mykey")
    }



    def testWithTransaction() {
        redisService.withRedis { Jedis redis ->
            assertNull redis.get("foo")
            redisService.withTransaction { Transaction transaction ->
                transaction.set("foo", "bar")
                assertNull redis.get("foo")
            }
            assertEquals "bar", redis.get("foo")
        }
    }

    def testPropertyMissingGetterRetrievesStringValue() {
        assertNull redisService.foo

        redisService.withRedis { Jedis redis ->
            redis.set("foo", "bar")
        }

        assertEquals "bar", redisService.foo
    }

    def testPropertyMissingSetterSetsStringValue() {
        redisService.withRedis { Jedis redis ->
            assertNull redis.foo
        }

        redisService.foo = "bar"

        redisService.withRedis { Jedis redis ->
            assertEquals "bar", redis.foo
        }
    }

    def testMethodMissingDelegatesToJedis() {
        assertNull redisService.foo

        redisService.set("foo", "bar")

        assertEquals "bar", redisService.foo
    }

    def testMethodNotOnJedisThrowsMethodMissingException() {
        def result = shouldFail {
            redisService.methodThatDoesNotExistAndNeverWill()
        }

        assert result?.startsWith("No signature of method: redis.clients.jedis.Jedis.methodThatDoesNotExistAndNeverWill")
    }
}
