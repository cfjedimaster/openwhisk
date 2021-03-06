/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.container.test

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.ErrorLevel
import common.StreamLogging
import common.WskActorSystem
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dockerEndpoint
import whisk.core.WhiskConfig.edgeHostName
import whisk.core.WhiskConfig.invokerSerializeDockerOp
import whisk.core.WhiskConfig.invokerSerializeDockerPull
import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.container.Container
import whisk.core.container.ContainerPool
import whisk.core.entity.AuthKey
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.Exec
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore

/**
 * Unit tests for ContainerPool and, by association, Container and WhiskContainer.
 */
@RunWith(classOf[JUnitRunner])
class ContainerPoolTests extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with WskActorSystem
    with StreamLogging {

    implicit val transid = TransactionId.testing

    val config = new WhiskConfig(
        WhiskEntityStore.requiredProperties ++
            WhiskAuthStore.requiredProperties ++
            ContainerPool.requiredProperties ++
            Map(selfDockerEndpoint -> "localhost",
                dockerEndpoint -> null,
                edgeHostName -> "localhost",
                invokerSerializeDockerOp -> "true",
                invokerSerializeDockerPull -> "true"))

    assert(config.isValid)

    val pool = new ContainerPool(config, 0, ErrorLevel, true, true)
    pool.logDir = "/tmp"

    val datastore = WhiskEntityStore.datastore(config)

    override def afterAll() {
        println("Shutting down store connections")
        datastore.shutdown()
        super.afterAll()
    }

    /**
     * Starts (and returns) a container running ubuntu image running echo on the given test word.
     * Also checks that the test word shows up in the docker logs.
     */
    def getEcho(word: String): Container = {
        val conOpt = pool.getByImageName("ubuntu", Array("/bin/echo", word))
        assert(conOpt isDefined) // we must be able to start the container
        val con = conOpt.getOrElse(null)
        Thread.sleep(1000) // docker run has no guarantee how far along the process is
        assert(con.getLogs().contains(word)) // the word must be in the docker logs
        con
    }

    /*
     * Start a new container that stays around via sleep.
     */
    def getSleep(duration: Int): Container = {
        val conOpt = pool.getByImageName("ubuntu", Array("/bin/sleep", duration.toString()))
        assert(conOpt isDefined) // we must be able to start the container
        conOpt.getOrElse(null)
    }

    /*
     * Ensure pool is empty/clean.
     */
    def ensureClean() = {
        pool.enableGC()
        pool.forceGC()
        Thread.sleep(2 * pool.gcFrequency.toMillis + 1500L) // GC should collect this by now
        assert(pool.idleCount() == 0)
        assert(pool.activeCount() == 0)
    }

    /*
     * Does a container with the given prefix exist?
     */
    def poolHasContainerIdPrefix(containerIdPrefix: String) = {
        val states = pool.listAll()
        states.find { _.id.hash.contains(containerIdPrefix) }.isDefined
    }

    behavior of "ContainerPool"

    after {
        ensureClean()
    }

    it should "be empty when it starts" in {
        assert(pool.idleCount() == 0)
        assert(pool.activeCount() == 0)
    }

    it should "allow getting container by image name, run it, retrieve logs, return it, force GC, check via docker ps" in {
        pool.disableGC()
        val startIdleCount = pool.idleCount()
        val container = getEcho("abracadabra")
        val containerIdPrefix = container.containerIdPrefix
        assert(poolHasContainerIdPrefix(containerIdPrefix)) // container must be around
        pool.putBack(container) // contractually, user must let go of con at this point
        assert(pool.idleCount() == startIdleCount + 1)
        pool.enableGC()
        pool.forceGC() // force all containers in pool to be freed
        Thread.sleep(2 * pool.gcFrequency.toMillis + 1500L) // GC should collect this by now
        assert(!poolHasContainerIdPrefix(containerIdPrefix)) // container must be gone by now
        assert(pool.idleCount() == 0)
    }

    it should "respect maxIdle by shooting a container on a putBack that could exceed it" in {
        ensureClean()
        pool.maxIdle = 1
        val c1 = getEcho("quasar")
        val c2 = getEcho("pulsar")
        val p1 = c1.containerIdPrefix
        val p2 = c2.containerIdPrefix
        assert(pool.activeCount() == 2)
        assert(pool.idleCount() == 0)
        pool.putBack(c1)
        assert(pool.activeCount() == 1)
        assert(pool.idleCount() == 1)
        pool.putBack(c2)
        assert(pool.activeCount() == 0)
        assert(pool.idleCount() == 1) // because c1 got shot
        pool.resetMaxIdle()
    }

    it should "respect activeIdle by blocking a getContainer until another is returned" in {
        ensureClean()
        pool.maxActive = 1
        val c1 = getEcho("hocus")
        var c1Back = false
        val f = Future { Thread.sleep(3000); c1Back = true; pool.putBack(c1) }
        val c2 = getEcho("pocus")
        assert(c1Back) // make sure c2 is not available before c1 is put back
        pool.putBack(c2)
        pool.resetMaxActive()
    }

    it should "also perform automatic GC with a settable threshold, invoke same action afterwards, another GC" in {
        ensureClean();
        pool.gcThreshold = 1.seconds
        val container = getEcho("hocus pocus")
        val containerIdPrefix = container.containerIdPrefix
        assert(poolHasContainerIdPrefix(containerIdPrefix)) // container must be around
        pool.putBack(container); // contractually, user must let go of con at this point
        // TODO: replace this with GC count so we don't break abstraction by knowing the GC check freq.  (!= threshold)
        Thread.sleep(2 * pool.gcFrequency.toMillis + 4000L) // GC should collect this by now
        assert(!poolHasContainerIdPrefix(containerIdPrefix)) // container must be gone by now
        // Do it again now
        val container2 = getEcho("hocus pocus")
        val containerIdPrefix2 = container2.containerIdPrefix
        assert(poolHasContainerIdPrefix(containerIdPrefix2)) // container must be around
        pool.putBack(container2)
        pool.resetGCThreshold()
    }

    // Lower it some more by parameterizing GC thresholds

    it should "be able to go through 15 containers without thrashing the system" in {
        ensureClean()
        val max = 15
        for (i <- List.range(0, max)) {
            val name = "foobar" + i
            val action = makeHelloAction(name, i)
            pool.getAction(action, defaultAuth) match {
                case (con, initResult) => {
                    val str = "QWERTY" + i.toString()
                    con.run(str, (20000 + i).toString()) // payload + activationId
                    if (i == max - 1) {
                        Thread.sleep(1000)
                        assert(con.getLogs().contains(str))
                    }
                    pool.putBack(con)
                }
            } // match
        } // for
    }

    private val defaultNamespace = EntityPath("container pool test")
    private val defaultAuth = AuthKey()

    /*
     * Create an action with the given name that print hello_N payload !
     * where N is specified.
     */
    private def makeHelloAction(name: String, index: Integer): WhiskAction = {
        val code = """console.log('ABCXYZ'); function main(msg) { console.log('hello_${index}', msg.payload+'!');} """
        WhiskAction(defaultNamespace, EntityName(name), Exec.js(code))
    }

    it should "be able to start a nodejs action with init, do a run, return to pool, do another get testing reuse, another run" in {
        ensureClean()
        val action = makeHelloAction("foobar", 0)
        // Make a whisk container and test init and a push
        val (con, initRes) = pool.getAction(action, defaultAuth)
        Thread.sleep(1000)
        assert(con.getLogs().contains("ABCXYZ"))
        con.run("QWERTY", "55555") // payload + activationId
        Thread.sleep(1000)
        assert(con.getLogs().contains("QWERTY"))
        pool.putBack(con)
        // Test container reuse
        val (con2, _) = pool.getAction(action, defaultAuth)
        assert(con == con2) // check re-use
        con.run("ASDFGH", "4444") // payload + activationId
        Thread.sleep(1000)
        assert(con.getLogs().contains("ASDFGH"))
        pool.putBack(con)
    }

    /*
     * Create an action that will crash the container after succesfully returning
     */
    private def makeCrashingAction(name: String, timeToLiveMs: Integer): WhiskAction = {
        val code = s"""function main(msg) { console.log('I expect you to die'); setTimeout(function(){ process.exit(1); }, ${timeToLiveMs}); }"""
        WhiskAction(defaultNamespace, EntityName(name), Exec.js(code))
    }

    it should "cleanly handle a container that crashes" in {
        ensureClean()
        val action = makeCrashingAction("NoMrBond", 50)

        val (con, initRes) = pool.getAction(action, defaultAuth)
        Thread.sleep(1000)

        con.run("NoMrBond", "1007")
        Thread.sleep(1000)
        assert(con.getLogs().contains("I expect you to die"))
        pool.putBack(con)

        // create a new container for this action. However, since the previous
        // container crashed, this should not be the same container
        Thread.sleep(2000)
        val (con2, _) = pool.getAction(action, defaultAuth)
        assert(con != con2)
        pool.putBack(con2)
    }
}
