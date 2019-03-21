/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * A synchronization point at which threads can pair and swap elements
 * within pairs.  Each thread presents some object on entry to the
 * {@link #exchange exchange} method, matches with a partner thread,
 * and receives its partner's object on return.  An Exchanger may be
 * viewed as a bidirectional form of a {@link SynchronousQueue}.
 * Exchangers may be useful in applications such as genetic algorithms
 * and pipeline designs.
 *
 * <p><b>Sample Usage:</b>
 * Here are the highlights of a class that uses an {@code Exchanger}
 * to swap buffers between threads so that the thread filling the
 * buffer gets a freshly emptied one when it needs it, handing off the
 * filled one to the thread emptying the buffer.
 * <pre> {@code
 * class FillAndEmpty {
 *   Exchanger<DataBuffer> exchanger = new Exchanger<>();
 *   DataBuffer initialEmptyBuffer = ... a made-up type
 *   DataBuffer initialFullBuffer = ...
 *
 *   class FillingLoop implements Runnable {
 *     public void run() {
 *       DataBuffer currentBuffer = initialEmptyBuffer;
 *       try {
 *         while (currentBuffer != null) {
 *           addToBuffer(currentBuffer);
 *           if (currentBuffer.isFull())
 *             currentBuffer = exchanger.exchange(currentBuffer);
 *         }
 *       } catch (InterruptedException ex) { ... handle ... }
 *     }
 *   }
 *
 *   class EmptyingLoop implements Runnable {
 *     public void run() {
 *       DataBuffer currentBuffer = initialFullBuffer;
 *       try {
 *         while (currentBuffer != null) {
 *           takeFromBuffer(currentBuffer);
 *           if (currentBuffer.isEmpty())
 *             currentBuffer = exchanger.exchange(currentBuffer);
 *         }
 *       } catch (InterruptedException ex) { ... handle ...}
 *     }
 *   }
 *
 *   void start() {
 *     new Thread(new FillingLoop()).start();
 *     new Thread(new EmptyingLoop()).start();
 *   }
 * }}</pre>
 *
 * <p>Memory consistency effects: For each pair of threads that
 * successfully exchange objects via an {@code Exchanger}, actions
 * prior to the {@code exchange()} in each thread
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those subsequent to a return from the corresponding {@code exchange()}
 * in the other thread.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <V> The type of objects that may be exchanged
 */
public class Exchanger<V> {

    /*
     * Overview: The core algorithm is, for an exchange "slot",
     * and a participant (caller) with an item:
     *
     * for (;;) {
     *   if (slot is empty) {                       // offer
     *     place item in a Node;
     *     if (can CAS slot from empty to node) {
     *       wait for release;
     *       return matching item in node;
     *     }
     *   }
     *   else if (can CAS slot from node to empty) { // release
     *     get the item in node;
     *     set matching item in node;
     *     release waiting thread;
     *   }
     *   // else retry on CAS failure
     * }
     *
     * This is among the simplest forms of a "dual data structure" --
     * see Scott and Scherer's DISC 04 paper and
     * http://www.cs.rochester.edu/research/synchronization/pseudocode/duals.html
     *
     * This works great in principle. But in practice, like many
     * algorithms centered on atomic updates to a single location, it
     * scales horribly when there are more than a few participants
     * using the same Exchanger. So the implementation instead uses a
     * form of elimination arena, that spreads out this contention by
     * arranging that some threads typically use different slots,
     * while still ensuring that eventually, any two parties will be
     * able to exchange items. That is, we cannot completely partition
     * across threads, but instead give threads arena indices that
     * will on average grow under contention and shrink under lack of
     * contention. We approach this by defining the Nodes that we need
     * anyway as ThreadLocals, and include in them per-thread index
     * and related bookkeeping state. (We can safely reuse per-thread
     * nodes rather than creating them fresh each time because slots
     * alternate between pointing to a node vs null, so cannot
     * encounter ABA problems. However, we do need some care in
     * resetting them between uses.)
     *
     * Implementing an effective arena requires allocating a bunch of
     * space, so we only do so upon detecting contention (except on
     * uniprocessors, where they wouldn't help, so aren't used).
     * Otherwise, exchanges use the single-slot slotExchange method.
     * On contention, not only must the slots be in different
     * locations, but the locations must not encounter memory
     * contention due to being on the same cache line (or more
     * generally, the same coherence unit).  Because, as of this
     * writing, there is no way to determine cacheline size, we define
     * a value that is enough for common platforms.  Additionally,
     * extra care elsewhere is taken to avoid other false/unintended
     * sharing and to enhance locality, including adding padding (via
     * @Contended) to Nodes, embedding "bound" as an Exchanger field.
     *
     * The arena starts out with only one used slot. We expand the
     * effective arena size by tracking collisions; i.e., failed CASes
     * while trying to exchange. By nature of the above algorithm, the
     * only kinds of collision that reliably indicate contention are
     * when two attempted releases collide -- one of two attempted
     * offers can legitimately fail to CAS without indicating
     * contention by more than one other thread. (Note: it is possible
     * but not worthwhile to more precisely detect contention by
     * reading slot values after CAS failures.)  When a thread has
     * collided at each slot within the current arena bound, it tries
     * to expand the arena size by one. We track collisions within
     * bounds by using a version (sequence) number on the "bound"
     * field, and conservatively reset collision counts when a
     * participant notices that bound has been updated (in either
     * direction).
     *
     * The effective arena size is reduced (when there is more than
     * one slot) by giving up on waiting after a while and trying to
     * decrement the arena size on expiration. The value of "a while"
     * is an empirical matter.  We implement by piggybacking on the
     * use of spin->yield->block that is essential for reasonable
     * waiting performance anyway -- in a busy exchanger, offers are
     * usually almost immediately released, in which case context
     * switching on multiprocessors is extremely slow/wasteful.  Arena
     * waits just omit the blocking part, and instead cancel. The spin
     * count is empirically chosen to be a value that avoids blocking
     * 99% of the time under maximum sustained exchange rates on a
     * range of test machines. Spins and yields entail some limited
     * randomness (using a cheap xorshift) to avoid regular patterns
     * that can induce unproductive grow/shrink cycles. (Using a
     * pseudorandom also helps regularize spin cycle duration by
     * making branches unpredictable.)  Also, during an offer, a
     * waiter can "know" that it will be released when its slot has
     * changed, but cannot yet proceed until match is set.  In the
     * mean time it cannot cancel the offer, so instead spins/yields.
     * Note: It is possible to avoid this secondary check by changing
     * the linearization point to be a CAS of the match field (as done
     * in one case in the Scott & Scherer DISC paper), which also
     * increases asynchrony a bit, at the expense of poorer collision
     * detection and inability to always reuse per-thread nodes. So
     * the current scheme is typically a better tradeoff.
     *
     * On collisions, indices traverse the arena cyclically in reverse
     * order, restarting at the maximum index (which will tend to be
     * sparsest) when bounds change. (On expirations, indices instead
     * are halved until reaching 0.) It is possible (and has been
     * tried) to use randomized, prime-value-stepped, or double-hash
     * style traversal instead of simple cyclic traversal to reduce
     * bunching.  But empirically, whatever benefits these may have
     * don't overcome their added overhead: We are managing operations
     * that occur very quickly unless there is sustained contention,
     * so simpler/faster control policies work better than more
     * accurate but slower ones.
     *
     * Because we use expiration for arena size control, we cannot
     * throw TimeoutExceptions in the timed version of the public
     * exchange method until the arena size has shrunken to zero (or
     * the arena isn't enabled). This may delay response to timeout
     * but is still within spec.
     *
     * Essentially all of the implementation is in methods
     * slotExchange and arenaExchange. These have similar overall
     * structure, but differ in too many details to combine. The
     * slotExchange method uses the single Exchanger field "slot"
     * rather than arena array elements. However, it still needs
     * minimal collision detection to trigger arena construction.
     * (The messiest part is making sure interrupt status and
     * InterruptedExceptions come out right during transitions when
     * both methods may be called. This is done by using null return
     * as a sentinel to recheck interrupt status.)
     *
     * As is too common in this sort of code, methods are monolithic
     * because most of the logic relies on reads of fields that are
     * maintained as local variables so can't be nicely factored --
     * mainly, here, bulky spin->yield->block/cancel code.  Note that
     * field Node.item is not declared as volatile even though it is
     * read by releasing threads, because they only do so after CAS
     * operations that must precede access, and all uses by the owning
     * thread are otherwise acceptably ordered by other operations.
     * (Because the actual points of atomicity are slot CASes, it
     * would also be legal for the write to Node.match in a release to
     * be weaker than a full volatile write. However, this is not done
     * because it could allow further postponement of the write,
     * delaying progress.)
     */

    /**
     * The index distance (as a shift value) between any two used slots
     * in the arena, spacing them out to avoid false sharing.
     *
     * 任何两个使用的槽之间的索引距离 ，将他们隔开避免错误共享
     */
    private static final int ASHIFT = 5;

    /**
     * The maximum supported arena index. The maximum allocatable
     * arena size is MMASK + 1. Must be a power of two minus one, less
     * than (1<<(31-ASHIFT)). The cap of 255 (0xff) more than suffices
     * for the expected scaling limits of the main algorithms.
     */
    private static final int MMASK = 0xff;

    /**
     * Unit for sequence/version bits of bound field. Each successful
     * change to the bound also adds SEQ.
     */
    private static final int SEQ = MMASK + 1;

    /** The number of CPUs, for sizing and spin control */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The maximum slot index of the arena: The number of slots that
     * can in principle hold all threads without contention, or at
     * most the maximum indexable value.
     */
    static final int FULL = (NCPU >= (MMASK << 1)) ? MMASK : NCPU >>> 1;

    /**
     * The bound for spins while waiting for a match. The actual
     * number of iterations will on average be about twice this value
     * due to randomization. Note: Spinning is disabled when NCPU==1.
     */
    private static final int SPINS = 1 << 10;

    /**
     * Value representing null arguments/returns from public
     * methods. Needed because the API originally didn't disallow null
     * arguments, which it should have.
     * 当传入null 用NULL_ITEM 代替
     */
    private static final Object NULL_ITEM = new Object();

    /**
     * Sentinel value returned by internal exchange methods upon
     * timeout, to avoid need for separate timed versions of these
     * methods.
     */
    private static final Object TIMED_OUT = new Object();

    /**
     * Nodes hold partially exchanged data, plus other per-thread
     * bookkeeping. Padded via @Contended to reduce memory contention.
     */
    @jdk.internal.vm.annotation.Contended static final class Node {

        //数组槽的索引值
        int index;              // Arena index
        int bound;              // Last recorded value of Exchanger.bound
        int collides;           // Number of CAS failures at current bound
        int hash;               // Pseudo-random for spins
        //这个线程需要交换的数据
        Object item;            // This thread's current item
        volatile Object match;  // Item provided by releasing thread
        //
        volatile Thread parked; // Set to this thread when parked, else null
    }

    /** The corresponding thread local class */
    static final class Participant extends ThreadLocal<Node> {
        public Node initialValue() { return new Node(); }
    }

    /**
     * Per-thread state.
     */
    private final Participant participant;

    /**
     * Elimination array; null until enabled (within slotExchange).
     * Element accesses use emulation of volatile gets and CAS.
     */
    private volatile Node[] arena;

    /**
     * Slot used until contention detected.
     */
    private volatile Node slot;

    /**
     * The index of the largest valid arena position, OR'ed with SEQ
     * number in high bits, incremented on each update.  The initial
     * update from 0 to SEQ is used to ensure that the arena array is
     * constructed only once.
     */
    private volatile int bound;

    /**
     * Exchange function when arenas enabled. See above for explanation.
     *
     * @param item the (non-null) item to exchange
     * @param timed true if the wait is timed
     * @param ns if timed, the maximum wait time, else 0L
     * @return the other thread's item; or null if interrupted; or
     * TIMED_OUT if timed and timed out
     *
     * 多槽交换的方法
     * 多槽的交换大致思想就是：当一个线程来交换的时候，如果”第一个”槽位是空的，那么自己就在那里等待，
     * 如果发现”第一个”槽位有等待线程，那么就直接交换，如果交换失败，说明其它线程在进行交换，
     * 那么就往后挪一个槽位，如果有数据就交换，没数据就等一会，但是不会阻塞在这里，在这里等了一会，
     * 发现还没有其它线程来交换数据，那么就往“第一个”槽位的方向挪，如果反复这样过后，挪到了第一个槽位，
     * 没有线程来交换数据了，那么自己就在”第一个”槽位阻塞等待。简单来说，如果有竞争冲突，那么就寻找后面的槽位，
     * 在后面的槽位等待一定时间，没有线程来交换， 那么就又往前挪。
     * ---------------------
     *
     * 这段话是从这个博主那抄过来的
     * 作者：zthgreat
     * 来源：CSDN
     * 原文：https://blog.csdn.net/u014634338/article/details/78385521
     * 版权声明：本文为博主原创文章，转载请附上博文链接！
     */
    private final Object arenaExchange(Object item, boolean timed, long ns) {
        Node[] a = arena;
        int alen = a.length;
        //当前线程的node p
        Node p = participant.get();
        for (int i = p.index;;) {                      // access slot at i
            int b, m, c;
            //根据p.index算出所在数组的索引j
            int j = (i << ASHIFT) + ((1 << ASHIFT) - 1);
            if (j < 0 || j >= alen)
                j = alen - 1;
            // q为数组j索引所在节点
            Node q = (Node)AA.getAcquire(a, j);
            //如果 q不为空就进行交换 交换成功那么就唤醒q所在线程
            if (q != null && AA.compareAndSet(a, j, q, null)) {
                Object v = q.item;                     // release
                q.match = item;
                Thread w = q.parked;
                if (w != null)
                    LockSupport.unpark(w);
                return v;
            }
            // bound 是最大的有效的 位置，和MMASK相与，得到真正的存储数据的索引最大值
            // boud赋值给m ，m下面会用到
            else if (i <= (m = (b = bound) & MMASK) && q == null) {
                //如果q为空并且i在范围之内
                //将需要交换的数据交给p
                p.item = item;                         // offer
                //设置数组arean 索引j的值为p 等待其它线程来交换
                if (AA.compareAndSet(a, j, null, p)) {
                    long end = (timed && m == 0) ? System.nanoTime() + ns : 0L;
                    Thread t = Thread.currentThread(); // wait
                    //开始自选
                    for (int h = p.hash, spins = SPINS;;) {
                        Object v = p.match;
                        //v != null 说明已经有线程来交换过了
                        if (v != null) {
                            //交换数据 返回得到的数据 并还原相关设置
                            MATCH.setRelease(p, null);
                            p.item = null;             // clear for next use
                            p.hash = h;
                            return v;
                        }
                        // match为空的话，就在这里进行一点的自炫
                        else if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0)                // initialize hash
                                h = SPINS | (int)t.getId();
                            else if (h < 0 &&          // approx 50% true
                                     (--spins & ((SPINS >>> 1) - 1)) == 0)
                                Thread.yield();        // two yields per wait
                        }
                        // j索引的位置已经不是p了，但是match还是为空
                        //说明有线程来交换了，但是mactch值还没有交换
                        // 重置spins 再自旋一会
                        else if (AA.getAcquire(a, j) != p)
                            spins = SPINS;       // releaser hasn't set match yet

                        //当m==0时 说明已经已经是第一个了，还是没有人来交换，那就正式阻塞自己
                        else if (!t.isInterrupted() && m == 0 &&
                                 (!timed ||
                                  (ns = end - System.nanoTime()) > 0L)) {
                            p.parked = t;              // minimize window
                            if (AA.getAcquire(a, j) == p) {
                                if (ns == 0L)
                                    LockSupport.park(this);
                                else
                                    LockSupport.parkNanos(this, ns);
                            }
                            p.parked = null;
                        }
                        //索引j还是p 那就将j位置设置为空
                        else if (AA.getAcquire(a, j) == p &&
                                 AA.compareAndSet(a, j, p, null)) {
                            //更新bound
                            if (m != 0)                // try to shrink
                                BOUND.compareAndSet(this, b, b + SEQ - 1);
                            p.item = null;
                            p.hash = h;
                            //减小索引值 从最后的索引值慢慢的往第一个移动
                            i = p.index >>>= 1;        // descend
                            if (Thread.interrupted())
                                return null;
                            if (timed && m == 0 && ns <= 0L)
                                return TIMED_OUT;
                            break;                     // expired; restart
                        }
                    }
                }
                else
                    //占据失败
                    p.item = null;                     // clear offer
            }
            // i不在范围内
            else {

                //bound 变了
                if (p.bound != b) {
                    // stale; reset
                    // 更新b
                    p.bound = b;
                    p.collides = 0;
                    //如果当前i在最大索引位置，那么递减一位
                    i = (i != m || m == 0) ? m : m - 1;
                }
                // 这里有点没有看懂
                else if ((c = p.collides) < m || m == FULL ||
                         !BOUND.compareAndSet(this, b, b + SEQ + 1)) {
                    p.collides = c + 1;
                    // i=0 那么就从m开始，否则递减i
                    i = (i == 0) ? m : i - 1;          // cyclically traverse
                }
                else
                    //递增
                    i = m + 1;                         // grow
                p.index = i;
            }
        }
    }

    /**
     * Exchange function used until arenas enabled. See above for explanation.
     *
     * @param item the item to exchange
     * @param timed true if the wait is timed
     * @param ns if timed, the maximum wait time, else 0L
     * @return the other thread's item; or null if either the arena
     * was enabled or the thread was interrupted before completion; or
     * TIMED_OUT if timed and timed out
     *
     *
     *
     * 这个方法是当只有单槽位的时候进行数据交换
     * 第一种情况  slot不为空 尝试cas替换slot为null并成功，唤醒阻塞的线程，
     *           将要替换的item赋值给阻塞node.match 返回阻塞node.item
     *           返回 要的值，当前slot为null
     *
     * 第二种情况  slot不为空 cas失败，bound为0，则将创建arena空数组，并进入下次自旋
     *           如果下次进来发现slot被人抢走了，并且arena也不为空 则返回null
     *           返回null arena不为null slot为 null
     *
     * slot如果存在，线程会一直抢直到抢到或者arena不为空，
     *
     *
     * 第三种情况  当slot为空，并且arena也为空时 首先会尝试下占领slot如果成功就会break，
     *           失败的话，说明slot被其他线程占领，又要进入第一第二种情况
     *
     * 接下来 如果当前线程占领了slot 再进行一定程度的自旋，
     * （这里有个疑问 一个线程占领了slot并开始自旋，在自旋过程中，另外个进场进来进入第一种情况把
     *   slot cas 为null 第一个线程岂不是一直自旋下去 ）
     *  (q.match = item;  漏看了这么一行代码，坑爹)
     *
     *  再自旋过程中如果有另外线程又占领了slot，那么该线程的match则被复制为那个线程的item,并返回item
     *
     *  如果没有线程来抢走
     *  arena为空则阻塞当前线程
     *  arena不为空则将slot设置成null 并返回null 并将MACTCH设置成当前节点（这种情况怎么才会发生呢。。）
     *
     *
     *
     */
    private final Object slotExchange(Object item, boolean timed, long ns) {
        //获取当前线程的节点
        Node p = participant.get();
        Thread t = Thread.currentThread();
        //线程中断立即返回
        if (t.isInterrupted()) // preserve interrupt status so caller can recheck
            return null;

        //自旋
        for (Node q;;) {
            // slot 不为null 说明已有线程将数据插入slot中并阻塞等待唤醒
            if ((q = slot) != null) {
                //尝试cas替换
                if (SLOT.compareAndSet(this, q, null)) {
                    // 交换的数据
                    Object v = q.item;
                    q.match = item;
                    //获取阻塞的线程并唤醒
                    Thread w = q.parked;
                    if (w != null)
                        LockSupport.unpark(w);
                    return v;
                }
                //cas失败 创建arena数组 bound为0说明数组还没有创建 cas bound 为256
                // create arena on contention, but continue until slot null
                if (NCPU > 1 && bound == 0 &&
                    BOUND.compareAndSet(this, 0, SEQ))
                    arena = new Node[(FULL + 2) << ASHIFT];
            }
            //如果arena != null，直接返回，进入arenaExchange 多个槽位的交换
            else if (arena != null)
                return null; // caller must reroute to arenaExchange
            else {
                //如果slot为null 并且area也为null 则将当前线程的node占领slot
                p.item = item;
                if (SLOT.compareAndSet(this, null, p))
                    break;
                p.item = null;
            }
        }

        // await release
        int h = p.hash;
        //默认end都为0L
        long end = timed ? System.nanoTime() + ns : 0L;

        // 自旋次数 NCPU<=1 禁用自旋
        int spins = (NCPU > 1) ? SPINS : 1;
        Object v;
        //进入自旋
        while ((v = p.match) == null) {
            //进行
            if (spins > 0) {
                h ^= h << 1; h ^= h >>> 3; h ^= h << 10;
                if (h == 0)
                    h = SPINS | (int)t.getId();
                else if (h < 0 && (--spins & ((SPINS >>> 1) - 1)) == 0)
                    Thread.yield();
            }
            //自旋完成后如果 slot变了 重置spins重新自旋
            else if (slot != p)
                spins = SPINS;
            //如果线程没有被中断并且arena为空
            else if (!t.isInterrupted() && arena == null &&
                     (!timed || (ns = end - System.nanoTime()) > 0L)) {
                p.parked = t;
                //如果当前线程已经占领了slot并且时间到了阻塞当前线程
                if (slot == p) {
                    if (ns == 0L)
                        LockSupport.park(this);
                    else
                        LockSupport.parkNanos(this, ns);
                }
                p.parked = null;
            }
            //当线程中断或者 arena不为空的时候 p 是否占领slot 是的话为null
            else if (SLOT.compareAndSet(this, p, null)) {
                v = timed && ns <= 0L && !t.isInterrupted() ? TIMED_OUT : null;
                break;
            }
        }
        MATCH.setRelease(p, null);
        p.item = null;
        p.hash = h;
        return v;
    }

    /**
     * Creates a new Exchanger.
     */
    public Exchanger() {
        participant = new Participant();
    }

    /**
     * Waits for another thread to arrive at this exchange point (unless
     * the current thread is {@linkplain Thread#interrupt interrupted}),
     * and then transfers the given object to it, receiving its object
     * in return.
     *
     * <p>If another thread is already waiting at the exchange point then
     * it is resumed for thread scheduling purposes and receives the object
     * passed in by the current thread.  The current thread returns immediately,
     * receiving the object passed to the exchange by that other thread.
     *
     * <p>If no other thread is already waiting at the exchange then the
     * current thread is disabled for thread scheduling purposes and lies
     * dormant until one of two things happens:
     * <ul>
     * <li>Some other thread enters the exchange; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * </ul>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * for the exchange,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * @param x the object to exchange
     * @return the object provided by the other thread
     * @throws InterruptedException if the current thread was
     *         interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public V exchange(V x) throws InterruptedException {
        Object v;
        Node[] a;
        Object item = (x == null) ? NULL_ITEM : x; // translate null args

        //当arena数组槽为空时，调用slotExchange方法
        //当arena数组槽不为空时或者slotExchange返回null 调用arenaExchange
        if (((a = arena) != null ||
             (v = slotExchange(item, false, 0L)) == null) &&
            ((Thread.interrupted() || // disambiguates null return
              (v = arenaExchange(item, false, 0L)) == null)))
            throw new InterruptedException();
        return (v == NULL_ITEM) ? null : (V)v;
    }

    /**
     * Waits for another thread to arrive at this exchange point (unless
     * the current thread is {@linkplain Thread#interrupt interrupted} or
     * the specified waiting time elapses), and then transfers the given
     * object to it, receiving its object in return.
     *
     * <p>If another thread is already waiting at the exchange point then
     * it is resumed for thread scheduling purposes and receives the object
     * passed in by the current thread.  The current thread returns immediately,
     * receiving the object passed to the exchange by that other thread.
     *
     * <p>If no other thread is already waiting at the exchange then the
     * current thread is disabled for thread scheduling purposes and lies
     * dormant until one of three things happens:
     * <ul>
     * <li>Some other thread enters the exchange; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>The specified waiting time elapses.
     * </ul>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * for the exchange,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link
     * TimeoutException} is thrown.  If the time is less than or equal
     * to zero, the method will not wait at all.
     *
     * @param x the object to exchange
     * @param timeout the maximum time to wait
     * @param unit the time unit of the {@code timeout} argument
     * @return the object provided by the other thread
     * @throws InterruptedException if the current thread was
     *         interrupted while waiting
     * @throws TimeoutException if the specified waiting time elapses
     *         before another thread enters the exchange
     */
    @SuppressWarnings("unchecked")
    public V exchange(V x, long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        Object v;
        Object item = (x == null) ? NULL_ITEM : x;
        long ns = unit.toNanos(timeout);
        if ((arena != null ||
             (v = slotExchange(item, true, ns)) == null) &&
            ((Thread.interrupted() ||
              (v = arenaExchange(item, true, ns)) == null)))
            throw new InterruptedException();
        if (v == TIMED_OUT)
            throw new TimeoutException();
        return (v == NULL_ITEM) ? null : (V)v;
    }

    // VarHandle mechanics
    private static final VarHandle BOUND;
    private static final VarHandle SLOT;
    private static final VarHandle MATCH;
    private static final VarHandle AA;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            BOUND = l.findVarHandle(Exchanger.class, "bound", int.class);
            SLOT = l.findVarHandle(Exchanger.class, "slot", Node.class);
            MATCH = l.findVarHandle(Node.class, "match", Object.class);
            AA = MethodHandles.arrayElementVarHandle(Node[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

}
