/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.util.concurrent;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.math.LongMath;

import java.util.concurrent.TimeUnit;

@GwtIncompatible
abstract class SmoothRateLimiter extends RateLimiter {
  /*
   * How is the RateLimiter designed, and why?
   *
   * RateLimiter的主要功能就是提供一个稳定的速率,实现方式就是通过限制请求流入的速度,比如计算请求等待合适的时间阈值.
   * The primary feature of a RateLimiter is its "stable rate", the maximum rate that is should
   * allow at normal conditions. This is enforced by "throttling" incoming requests as needed, i.e.
   * compute, for an incoming request, the appropriate throttle time, and make the calling thread
   * wait as much.
   *
   * 实现QPS速率的最简单的方式就是记住上一次请求的最后授权时间,然后保证1/QPS秒内不允许请求进入.
   * 比如QPS=5,如果我们保证最后一个被授权请求之后的200ms的时间内没有请求被授权,那么我们就达到了预期的速率.
   * 如果一个请求现在过来但是最后一个被授权请求是在100ms之前,那么我们就要求当前这个请求等待100ms.按照这个思路,
   * 请求15个新令牌(许可证)就需要3秒.
   *
   * The simplest way to maintain a rate of QPS is to keep the timestamp of the last granted
   * request, and ensure that (1/QPS) seconds have elapsed since then. For example, for a rate of
   * QPS=5 (5 tokens per second), if we ensure that a request isn't granted earlier than 200ms after
   * the last one, then we achieve the intended rate. If a request comes and the last request was
   * granted only 100ms ago, then we wait for another 100ms. At this rate, serving 15 fresh permits
   * (i.e. for an acquire(15) request) naturally takes 3 seconds.
   *
   * 有一点很重要:上面这个设计思路的RateLimiter记忆非常的浅,它的脑容量非常的小,只记得上一次被授权的请求的时间.
   * 如果RateLimiter的一个被授权请求q之前很长一段时间没有被使用会怎么样?
   * 这个RateLimiter会立马忘记过去这一段时间的利用不足,而只记得刚刚的请求q.
   *
   * It is important to realize that such a RateLimiter has a very superficial memory of the past:
   * it only remembers the last request. What if the RateLimiter was unused for a long period of
   * time, then a request arrived and was immediately granted? This RateLimiter would immediately
   * forget about that past underutilization(利用不足，未充分使用).
   * This may result in either underutilization or overflow,
   * depending on the real world consequences of not using the expected rate.
   *
   * 过去一段时间的利用不足意味着有过剩的资源是可以利用的.这种情况下,
   * RateLimiter应该加把劲(speed up for a while)将这些过剩的资源利用起来.
   * 比如在向网络中发生数据的场景(限流),过去一段时间的利用不足可能意味着网卡缓冲区是空的,
   * 这种场景下,我们是可以加速发送来将这些过程的资源利用起来.
   *
   * Past underutilization could mean that excess resources are available. Then, the RateLimiter
   * should speed up for a while, to take advantage of these resources. This is important when the
   * rate is applied to networking (limiting bandwidth), where past underutilization typically
   * translates to "almost empty buffers", which can be filled immediately.
   *
   * 另一方面,过去一段时间的利用不足可能意味着处理请求的服务器对即将到来的请求是准备不足的
   * (less ready for future requests),比如因为很长一段时间没有请求当前服务器的cache是陈旧的,
   * 进而导致即将到来的请求会触发一个昂贵的操作(比如重新刷新全量的缓存).
   *
   * On the other hand, past underutilization could mean that "the server responsible for handling
   * the request has become less ready for future requests", i.e. its caches become stale, and
   * requests become more likely to trigger expensive operations (a more extreme case of this
   * example is when a server has just booted, and it is mostly busy with getting itself up to
   * speed).
   *
   * 为了处理这种情况,RateLimiter中增加了一个维度的信息,就是过去一段时间的利用不足(past underutilization),
   * 代码中使用storedPermits变量表示.当没有利用不足这个变量为0,
   * 最大能达到maxStoredPermits(maxStoredPermits表示完全没有利用).因此,请求的令牌可能从两个地方来:
   *
   * To deal with such scenarios, we add an extra dimension, that of "past underutilization",
   * modeled by "storedPermits" variable. This variable is zero when there is no underutilization,
   * and it can grow up to maxStoredPermits, for sufficiently large underutilization. So, the
   * requested permits, by an invocation acquire(permits), are served from:
   *
   * 1.过去剩余的令牌(stored permits, 可能没有)
   * - stored permits (if available)
   *
   * 2.现有的令牌(fresh permits,当前这段时间还没用完的令牌)
   * - fresh permits (for any remaining permits)
   *
   * How this works is best explained with an example:
   *
   * 对一个每秒产生一个令牌的RateLimiter,每有一个没有使用令牌的一秒,我们就将storedPermits加1,
   * 如果RateLimiter在10秒都没有使用,则storedPermits变成10.0.这个时候,一个请求到来并请求三个令牌(acquire(3)),
   * 我们将从storedPermits中的令牌为其服务,storedPermits变为7.0.这个请求之后立马又有一个请求到来并请求10个令牌,
   * 我们将从storedPermits剩余的7个令牌给这个请求,剩下还需要三个令牌,我们将从RateLimiter新产生的令牌中获取.
   * 我们已经知道,RateLimiter每秒新产生1个令牌,就是说上面这个请求还需要的3个请求就要求其等待3秒.
   *
   * For a RateLimiter that produces 1 token per second, every second that goes by with the
   * RateLimiter being unused, we increase storedPermits by 1. Say we leave the RateLimiter unused
   * for 10 seconds (i.e., we expected a request at time X, but we are at time X + 10 seconds before
   * a request actually arrives; this is also related to the point made in the last paragraph), thus
   * storedPermits becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of
   * acquire(3) arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how
   * this is translated to throttling time is discussed later). Immediately after, assume that an
   * acquire(10) request arriving. We serve the request partly from storedPermits, using all the
   * remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits produced by the
   * rate limiter.
   *
   * 我们知道还需要3秒才能提供3个permit,如果我们对利用不足感兴趣,会希望优先使用过去的令牌,如果我们对溢出更感兴趣,
   * 我们会希望优先使用新的令牌,这样我们就需要一个阀门时间.
   *
   * We already know how much time it takes to serve 3 fresh permits: if the rate is
   * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7 stored
   * permits? As explained above, there is no unique answer. If we are primarily interested to deal
   * with underutilization, then we want stored permits to be given out /faster/ than fresh ones,
   * because underutilization = free resources for the taking. If we are primarily interested to
   * deal with overflow, then stored permits could be given out /slower/ than fresh ones. Thus, we
   * require a (different in each case) function that translates storedPermits to throtting time.
   *
   * 由storedPermitsToWaitTime提供这个功能,将storedPermits(1->maxStoredPermits)转换成1/rate(周期),
   * storedPermits可以知道未使用时间.
   *
   * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake). The
   * underlying model is a continuous function mapping storedPermits (from 0.0 to maxStoredPermits)
   * onto the 1/rate (i.e. intervals) that is effective at the given storedPermits. "storedPermits"
   * essentially measure unused time; we spend unused time buying/storing permits. Rate is
   * "permits / time", thus "1 / rate = time / permits". Thus, "1/rate" (time / permits) times
   * "permits" gives time, i.e., integrals on this function (which is what storedPermitsToWaitTime()
   * computes) correspond to minimum intervals between subsequent requests, for the specified number
   * of requested permits.
   *
   * Here is an example of storedPermitsToWaitTime: If storedPermits == 10.0, and we want 3 permits,
   * we take them from storedPermits, reducing them to 7.0, and compute the throttling for these as
   * a call to storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
   * evaluate the integral of the function from 7.0 to 10.0.
   *
   * Using integrals guarantees that the effect of a single acquire(3) is equivalent to {
   * acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc, since the integral
   * of the function in [7.0, 10.0] is equivalent to the sum of the integrals of [7.0, 8.0], [8.0,
   * 9.0], [9.0, 10.0] (and so on), no matter what the function is. This guarantees that we handle
   * correctly requests of varying weight (permits), /no matter/ what the actual function is - so we
   * can tweak the latter freely. (The only requirement, obviously, is that we can compute its
   * integrals).
   *
   * Note well that if, for this function, we chose a horizontal line, at height of exactly (1/QPS),
   * then the effect of the function is non-existent: we serve storedPermits at exactly the same
   * cost as fresh ones (1/QPS is the cost for each). We use this trick later.
   *
   * If we pick a function that goes /below/ that horizontal line, it means that we reduce the area
   * of the function, thus time. Thus, the RateLimiter becomes /faster/ after a period of
   * underutilization. If, on the other hand, we pick a function that goes /above/ that horizontal
   * line, then it means that the area (time) is increased, thus storedPermits are more costly than
   * fresh permits, thus the RateLimiter becomes /slower/ after a period of underutilization.
   *
   * 想象一个RateLimiter每秒产生一个令牌,现在完全没有使用(处于初始状态),
   * 一个昂贵的请求acquire(100)过来.如果我们选择让这个请求等待100秒再允许其执行,
   * 这显然很荒谬.我们为什么什么也不做而只是傻傻的等待100秒,一个更好的做法是允许这个请求立即执行(和acquire(1)没有区别),
   * 然后将随后到来的请求推迟到正确的时间点.这种策略,我们允许这个昂贵的任务立即执行,并将随后到来的请求推迟100秒.
   * 这种策略就是让任务的执行和等待同时进行.
   *
   * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
   * completely unused, and an expensive acquire(100) request comes. It would be nonsensical to just
   * wait for 100 seconds, and /then/ start the actual task. Why wait without doing anything? A much
   * better approach is to /allow/ the request right away (as if it was an acquire(1) request
   * instead), and postpone /subsequent/ requests as needed. In this version, we allow starting the
   * task immediately, and postpone by 100 seconds future requests, thus we allow for work to get
   * done in the meantime instead of waiting idly.
   *
   * 一个重要的结论:RateLimiter不会记最后一个请求,而是即下一个请求允许执行的时间.
   * 这也可以很直白的告诉我们到达下一个调度时间点的时间间隔.然后定一个一段时间未使用的Ratelimiter也很简单:
   * 下一个调度时间点已经过去,这个时间点和现在时间的差就是Ratelimiter多久没有被使用,
   * 我们会将这一段时间翻译成storedPermits.所有,如果每秒钟产生一个令牌(rate==1),
   * 并且正好每秒来一个请求,那么storedPermits就不会增长.
   *
   * This has important consequences: it means that the RateLimiter doesn't remember the time of the
   * _last_ request, but it remembers the (expected) time of the _next_ request. This also enables
   * us to tell immediately (see tryAcquire(timeout)) whether a particular timeout is enough to get
   * us to the point of the next scheduling time, since we always maintain that. And what we mean by
   * "an unused RateLimiter" is also defined by that notion: when we observe that the
   * "expected arrival time of the next request" is actually in the past, then the difference (now -
   * past) is the amount of time that the RateLimiter was formally unused, and it is that amount of
   * time which we translate to storedPermits. (We increase storedPermits with the amount of permits
   * that would have been produced in that idle time). So, if rate == 1 permit per second, and
   * arrivals come exactly one second after the previous, then storedPermits is _never_ increased --
   * we would only increase it for arrivals _later_ than the expected one second.
   */

    /**
     * This implements the following function where coldInterval = coldFactor * stableInterval.
     * <p>
     * <pre>
     *          ^ throttling
     *          |
     *    cold  +                  /
     * interval |                 /.
     *          |                / .
     *          |               /  .   ← "warmup period" is the area of the trapezoid between
     *          |              /   .     thresholdPermits and maxPermits
     *          |             /    .
     *          |            /     .
     *          |           /      .
     *   stable +----------/  WARM .
     * interval |          .   UP  .
     *          |          . PERIOD.
     *          |          .       .
     *        0 +----------+-------+--------------→ storedPermits
     *          0 thresholdPermits maxPermits
     * </pre>
     * <p>
     * Before going into the details of this particular function, let's keep in mind the basics:
     * <p>
     * <ol>
     * <li>The state of the RateLimiter (storedPermits) is a vertical line in this figure.
     * <li>When the RateLimiter is not used, this goes right (up to maxPermits)
     * <li>When the RateLimiter is used, this goes left (down to zero), since if we have
     * storedPermits, we serve from those first
     * <li>When _unused_, we go right at a constant rate! The rate at which we move to the right is
     * chosen as maxPermits / warmupPeriod. This ensures that the time it takes to go from 0 to
     * maxPermits is equal to warmupPeriod.
     * <li>When _used_, the time it takes, as explained in the introductory class note, is equal to
     * the integral of our function, between X permits and X-K permits, assuming we want to
     * spend K saved permits.
     * </ol>
     * <p>
     * <p>In summary, the time it takes to move to the left (spend K permits), is equal to the area of
     * the function of width == K.
     * <p>
     * <p>Assuming we have saturated demand, the time to go from maxPermits to thresholdPermits is
     * equal to warmupPeriod. And the time to go from thresholdPermits to 0 is warmupPeriod/2. (The
     * reason that this is warmupPeriod/2 is to maintain the behavior of the original implementation
     * where coldFactor was hard coded as 3.)
     * <p>
     * <p>It remains to calculate thresholdsPermits and maxPermits.
     * <p>
     * <ul>
     * <li>The time to go from thresholdPermits to 0 is equal to the integral of the function
     * between 0 and thresholdPermits. This is thresholdPermits * stableIntervals. By (5) it is
     * also equal to warmupPeriod/2. Therefore
     * <blockquote>
     * thresholdPermits = 0.5 * warmupPeriod / stableInterval
     * </blockquote>
     * <p>
     * <li>The time to go from maxPermits to thresholdPermits is equal to the integral of the
     * function between thresholdPermits and maxPermits. This is the area of the pictured
     * trapezoid, and it is equal to 0.5 * (stableInterval + coldInterval) * (maxPermits -
     * thresholdPermits). It is also equal to warmupPeriod, so
     * <blockquote>
     * maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval)
     * </blockquote>
     * <p>
     * </ul>
     */
    static final class SmoothWarmingUp extends SmoothRateLimiter {
        private final long warmupPeriodMicros;
        /**
         * The slope of the line from the stable interval (when permits == 0), to the cold interval
         * (when permits == maxPermits)
         */
        // 斜率
        private double slope;
        private double thresholdPermits;
        private double coldFactor;

        SmoothWarmingUp(
                SleepingStopwatch stopwatch, long warmupPeriod, TimeUnit timeUnit, double coldFactor) {
            super(stopwatch);
            this.warmupPeriodMicros = timeUnit.toMicros(warmupPeriod);
            this.coldFactor = coldFactor;
        }

        @Override
        void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
            double oldMaxPermits = maxPermits;
            // 假设QPS是10, 默认coldFactor是3,则stableIntervalMicros = 1/10 = 100ms
            // coldIntervalMicros = 300ms
            double coldIntervalMicros = stableIntervalMicros * coldFactor;
            // thresholdPermits = warmupPeriodMicros * (0.5 * QPS)
            // 假设QPS是10 warmup是1,那么thresholdPermits就是5
            // 假设QPS是10 warmup是2,那么thresholdPermits就是10
            thresholdPermits = 0.5 * warmupPeriodMicros / stableIntervalMicros;
            // QPS是10 那么maxPermits = thresholdPermits + 2.0 * warmupPeriodMicros / 4倍stableIntervalMicros
            // warmup是1,maxPermits就是10
            // warmup是2,maxPermits就是20
            // 在coldFactor为3时, maxPermits = 2thresholdPermits = warmupPeriodMicros * QPS
            maxPermits =
                    thresholdPermits + 2.0 * warmupPeriodMicros / (stableIntervalMicros + coldIntervalMicros);
            // 斜率计算
            // = 2(maxPermits - thresholdPermits) / QPS
            slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
            // 压缩或扩容storedPermits
            if (oldMaxPermits == Double.POSITIVE_INFINITY) {
                // if we don't special-case this, we would get storedPermits == NaN, below
                storedPermits = 0.0;
            } else {
                storedPermits =
                        (oldMaxPermits == 0.0)
                                ? maxPermits // initial state is cold
                                : storedPermits * maxPermits / oldMaxPermits;
            }
        }

        @Override
        long storedPermitsToWaitTime(double storedPermits, double storedPermitsToSpend) {
            // 目前storedPermits令牌是否已经大于thresholdPermits
            // 大于thresholdPermits就要先从大于的扣,不够再扣除thresholdPermits以下的
            // 不大于thresholdPermits直接可以从thresholdPermits扣除
            double availablePermitsAboveThreshold = storedPermits - thresholdPermits;
            long micros = 0;
            // measuring the integral on the right part of the function (the climbing line)
            // 需要用到大于thresholdPermits的permit
            if (availablePermitsAboveThreshold > 0.0) {
                // 需要多少大于阈值的thresholdPermits的permit
                double permitsAboveThresholdToTake = min(availablePermitsAboveThreshold, storedPermitsToSpend);
                // TODO(cpovirk): Figure out a good name for this variable.
                double length = permitsToTime(availablePermitsAboveThreshold)
                        + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake);
                micros = (long) (permitsAboveThresholdToTake * length / 2.0);
                // 需要多少阈值以下的
                storedPermitsToSpend -= permitsAboveThresholdToTake;
            }
            // measuring the integral on the left part of the function (the horizontal line)
            micros += (stableIntervalMicros * storedPermitsToSpend);
            return micros;
        }

        private double permitsToTime(double permits) {
            return stableIntervalMicros + permits * slope;
        }

        //maxPermits = 2thresholdPermits = warmupPeriodMicros * QPS
        @Override
        double coolDownIntervalMicros() {
            return warmupPeriodMicros / maxPermits;
        }
    }

    /**
     * This implements a "bursty" RateLimiter, where storedPermits are translated to zero throttling.
     * The maximum number of permits that can be saved (when the RateLimiter is unused) is defined in
     * terms of time, in this sense: if a RateLimiter is 2qps, and this time is specified as 10
     * seconds, we can save up to 2 * 10 = 20 permits.
     */
    // SmoothBursty使用storedPermits不需要额外等待时间。
    // 并且默认maxBurstSeconds未1，因此maxPermits为permitsPerSecond，
    // 即最多可以存储1秒的剩余令牌，比如QPS=5，则maxPermits=5.
    // (1).t=0,这时候storedPermits=0，请求1个令牌，等待时间=0；
    // (2).t=1,这时候storedPermits=3，请求3个令牌，等待时间=0；
    // (3).t=2,这时候storedPermits=4，请求10个令牌，等待时间=0，超前使用了2个令牌；
    // (4).t=3,这时候storedPermits=0，请求1个令牌，等待时间=0.5；
    static final class SmoothBursty extends SmoothRateLimiter {
        /**
         * The work (permits) of how many seconds can be saved up if this RateLimiter is unused?
         */
        final double maxBurstSeconds;

        SmoothBursty(SleepingStopwatch stopwatch, double maxBurstSeconds) {
            super(stopwatch);
            this.maxBurstSeconds = maxBurstSeconds;
        }

        @Override
        void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
            double oldMaxPermits = this.maxPermits;
            // 最大容量就是permitsPerSecond
            maxPermits = maxBurstSeconds * permitsPerSecond;
            if (oldMaxPermits == Double.POSITIVE_INFINITY) {
                // if we don't special-case this, we would get storedPermits == NaN, below
                storedPermits = maxPermits;
            } else {
                storedPermits =
                        (oldMaxPermits == 0.0)
                                ? 0.0 // initial state
                                : storedPermits * maxPermits / oldMaxPermits;
            }
        }

        // Brusty方式花费storedPermits不需要时间
        @Override
        long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
            return 0L;
        }

        @Override
        double coolDownIntervalMicros() {
            return stableIntervalMicros;
        }
    }

    /**
     * The currently stored permits.
     */
    double storedPermits;

    /**
     * The maximum number of stored permits.
     */
    double maxPermits;

    /**
     * The interval between two unit requests, at our stable rate. E.g., a stable rate of 5 permits
     * per second has a stable interval of 200ms.
     */
    // 稳定微秒间隔 比如一秒5个ticket就是200ms
    double stableIntervalMicros;

    /**
     * The time when the next request (no matter its size) will be granted. After granting a request,
     * this is pushed further in the future. Large requests push this further than small requests.
     */
    // 下次获取的时候需要减去的时间
    private long nextFreeTicketMicros = 0L; // could be either in the past or future

    private SmoothRateLimiter(SleepingStopwatch stopwatch) {
        super(stopwatch);
    }

    @Override
    final void doSetRate(double permitsPerSecond, long nowMicros) {
        resync(nowMicros);// 补充令牌
        double stableIntervalMicros = SECONDS.toMicros(1L) / permitsPerSecond;
        this.stableIntervalMicros = stableIntervalMicros;
        doSetRate(permitsPerSecond, stableIntervalMicros);
    }

    abstract void doSetRate(double permitsPerSecond, double stableIntervalMicros);

    @Override
    final double doGetRate() {
        return SECONDS.toMicros(1L) / stableIntervalMicros;
    }

    // 下次获取的时候需要减去的时间
    @Override
    final long queryEarliestAvailable(long nowMicros) {
        return nextFreeTicketMicros;
    }

    // reserveEarliestAvailable方法体内首先会去调用resync
    // （根据当前时间更新剩余的令牌数：storedPermits，以及nextFreeTickeMicros），
    // 然后根据所请求的令牌数：requiredPermits比如现在请求的100个令牌数以及当前可以使用的令牌数，
    // 取二者的最小值：storedPermitsToSpend，
    // 再根据请求的令牌数requiredPermits和上一步计算得出的storedPermitsToSpend计算出还需要的令牌数：
    // freshPermits，接着计算等待时间：waitMicros，
    // 在计算公式中stableIntervalMicros表示上一次请求到下一次请求允许的时间间隔的毫秒数，
    // stableIntervalMicros = 1/qps(创建ratelimiter类时传入的creat的参数)，
    // 此例中qps=10，stableIntervalMicros=1/10=100ms。
    // 最后更新下一次允许请求的时间nextFreeTickeMicros，并计算还剩余的令牌数：
    // 还剩余令牌数=当前剩余令牌数（storedPermits）-拿走的令牌（storedPermitsToSpend）。

    /**
     * @return 返回需要等待多久,同时更新storedPermits等信息
     */
    @Override
    final long reserveEarliestAvailable(int requiredPermits, long nowMicros) {
        // 如果上次请求过多不会更改nextFreeTicketMicros,返回后可能需要等待
        // 否则不需要等待
        resync(nowMicros);//补充令牌
        long returnValue = nextFreeTicketMicros;
        // 这次请求消耗的剩余令牌数目
        double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
        // 需要多少新的令牌
        double freshPermits = requiredPermits - storedPermitsToSpend;
        // 下次请求需要等待时间
        long waitMicros =
                storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend)
                        + (long) (freshPermits * stableIntervalMicros);

        this.nextFreeTicketMicros = LongMath.saturatedAdd(nextFreeTicketMicros, waitMicros);
        this.storedPermits -= storedPermitsToSpend;
        return returnValue;
    }

    /**
     * Translates a specified portion of our currently stored permits which we want to spend/acquire,
     * into a throttling time. Conceptually, this evaluates the integral of the underlying function we
     * use, for the range of [(storedPermits - permitsToTake), storedPermits].
     * <p>
     * <p>This always holds: {@code 0 <= permitsToTake <= storedPermits}
     */
    abstract long storedPermitsToWaitTime(double storedPermits, double permitsToTake);

    /**
     * 也就是未使用时多少时间能缓存一个permit
     * Returns the number of microseconds during cool down that we have to wait to get a new permit.
     */
    abstract double coolDownIntervalMicros();

    /**
     * Updates {@code storedPermits} and {@code nextFreeTicketMicros} based on the current time.
     */
    void resync(long nowMicros) {
        // if nextFreeTicket is in the past, resync to now
        if (nowMicros > nextFreeTicketMicros) {
            double newPermits = (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros();
            // 更新过去一段时间未使用的storedPermits
            storedPermits = min(maxPermits, storedPermits + newPermits);
            // 下一次请求以nextFreeTicketMicros确认能够产生多少permit
            nextFreeTicketMicros = nowMicros;
        }
    }
}
