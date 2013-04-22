/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor;


import com.lmax.disruptor.dsl.ProducerType;

/**
 * Ring based store of reusable entries containing the data representing
 * an event being exchanged between event producer and {@link EventProcessor}s.
 *
 * @param <E> implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class RingBuffer<E> implements Cursored, DataProvider<E>
{
    public static final long INITIAL_CURSOR_VALUE = Sequence.INITIAL_VALUE;

    private final int indexMask;
    private final Object[] entries;
    private final int bufferSize;
    private final Sequencer sequencer;

    /**
     * Construct a RingBuffer with the full option set.
     *
     * @param eventFactory to newInstance entries for filling the RingBuffer
     * @param sequencer sequencer to handle the ordering of events moving through the RingBuffer.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    RingBuffer(EventFactory<E> eventFactory,
               Sequencer       sequencer)
    {
        this.sequencer    = sequencer;
        this.bufferSize   = sequencer.getBufferSize();

        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.indexMask = bufferSize - 1;
        this.entries   = new Object[sequencer.getBufferSize()];
        fill(eventFactory);
    }

    /**
     * Create a new multiple producer RingBuffer with the specified wait strategy.
     *
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory,
                                                        int             bufferSize,
                                                        WaitStrategy    waitStrategy)
    {
        MultiProducerSequencer sequencer = new MultiProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    /**
     * Create a new multiple producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     *
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize)
    {
        return createMultiProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * Create a new single producer RingBuffer with the specified wait strategy.
     *
     * @see SingleProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory,
                                                         int             bufferSize,
                                                         WaitStrategy    waitStrategy)
    {
        SingleProducerSequencer sequencer = new SingleProducerSequencer(bufferSize, waitStrategy);

        return new RingBuffer<E>(factory, sequencer);
    }

    /**
     * Create a new single producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     *
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize)
    {
        return createSingleProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * Create a new Ring Buffer with the specified producer type (SINGLE or MULTI)
     *
     * @param producerType producer type to use {@link ProducerType}.
     * @param factory used to create events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> create(ProducerType    producerType,
                                           EventFactory<E> factory,
                                           int             bufferSize,
                                           WaitStrategy    waitStrategy)
    {
        switch (producerType)
        {
        case SINGLE:
            return createSingleProducer(factory, bufferSize, waitStrategy);
        case MULTI:
            return createMultiProducer(factory, bufferSize, waitStrategy);
        default:
            throw new IllegalStateException(producerType.toString());
        }
    }

    /**
     * <p>Get the event for a given sequence in the RingBuffer.</p>
     *
     * <p>This call has 2 uses.  Firstly use this call when publishing to a ring buffer.
     * After calling {@link RingBuffer#next()} use this call to get hold of the
     * preallocated event to fill with data before calling {@link RingBuffer#publish(long)}.</p>
     *
     * <p>Secondly use this call when consuming data from the ring buffer.  After calling
     * {@link SequenceBarrier#waitFor(long)} call this method with any value greater than
     * that your current consumer sequence and less than or equal to the value returned from
     * the {@link SequenceBarrier#waitFor(long)} method.</p>
     *
     * @param sequence for the event
     * @return the event for the given sequence
     */
    @SuppressWarnings("unchecked")
    public E get(long sequence)
    {
        return (E)entries[(int)sequence & indexMask];
    }

    /**
     * @deprecated Use {@link RingBuffer#get(long)}
     */
    @Deprecated
    public E getPreallocated(long sequence)
    {
        return get(sequence);
    }

    /**
     * @deprecated Use {@link RingBuffer#get(long)}
     */
    @Deprecated
    public E getPublished(long sequence)
    {
        return get(sequence);
    }

    /**
     * Increment and return the next sequence for the ring buffer.  Calls of this
     * method should ensure that they always publish the sequence afterward.  E.g.
     * <pre>
     * long sequence = ringBuffer.next();
     * try {
     *     Event e = ringBuffer.get(sequence);
     *     // Do some work with the event.
     * } finally {
     *     ringBuffer.publish(sequence);
     * }
     * </pre>
     * @see RingBuffer#publish(long)
     * @see RingBuffer#get(long)
     * @return The next sequence to publish to.
     */
    public long next()
    {
        return sequencer.next();
    }

    /**
     * The same functionality as {@link RingBuffer#next()}, but allows the caller to claim
     * the next n sequences.
     *
     * @see Sequencer#next(int)
     * @param n number of slots to claim
     * @return sequence number of the highest slot claimed
     */
    public long next(int n)
    {
        return sequencer.next(n);
    }

    /**
     * <p>Increment and return the next sequence for the ring buffer.  Calls of this
     * method should ensure that they always publish the sequence afterward.  E.g.
     * <pre>
     * long sequence = ringBuffer.next();
     * try {
     *     Event e = ringBuffer.get(sequence);
     *     // Do some work with the event.
     * } finally {
     *     ringBuffer.publish(sequence);
     * }
     * </pre>
     * <p>This method will not block if there is not space available in the ring
     * buffer, instead it will throw an {@link InsufficientCapacityException}.
     *
     *
     * @see RingBuffer#publish(long)
     * @see RingBuffer#get(long)
     * @return The next sequence to publish to.
     * @throws InsufficientCapacityException if the necessary space in the ring buffer is not available
     */
    public long tryNext() throws InsufficientCapacityException
    {
        return sequencer.tryNext();
    }

    /**
     * The same functionality as {@link RingBuffer#tryNext()}, but allows the caller to attempt
     * to claim the next n sequences.
     *
     * @param n number of slots to claim
     * @return sequence number of the highest slot claimed
     * @throws InsufficientCapacityException if the necessary space in the ring buffer is not available
     */
    public long tryNext(int n) throws InsufficientCapacityException
    {
        return sequencer.tryNext(n);
    }

    /**
     * Resets the cursor to a specific value.  This can be applied at any time, but it is worth not
     * that it is a racy thing to do and should only be used in controlled circumstances.  E.g. during
     * initialisation.
     *
     * @param sequence The sequence to reset too.
     * @throws IllegalStateException If any gating sequences have already been specified.
     */
    public void resetTo(long sequence)
    {
        sequencer.claim(sequence);
        sequencer.publish(sequence);
    }

    /**
     * Sets the cursor to a specific sequence and returns the preallocated entry that is stored there.  This
     * is another deliberatly racy call, that should only be done in controlled circumstances, e.g. initialisation.
     *
     * @param sequence The sequence to claim.
     * @return The preallocated event.
     */
    public E claimAndGetPreallocated(long sequence)
    {
        sequencer.claim(sequence);
        return get(sequence);
    }

    /**
     * Determines if a particular entry has been published.
     *
     * @param sequence The sequence to identify the entry.
     * @return If the value has been published or not.
     */
    public boolean isPublished(long sequence)
    {
        return sequencer.isAvailable(sequence);
    }

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     *
     * @param gatingSequences The sequences to add.
     */
    public void addGatingSequences(Sequence... gatingSequences)
    {
        sequencer.addGatingSequences(gatingSequences);
    }

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    public long getMinimumGatingSequence()
    {
        return sequencer.getMinimumSequence();
    }

    /**
     * Remove the specified sequence from this ringBuffer.
     *
     * @param sequence to be removed.
     * @return <tt>true</tt> if this sequence was found, <tt>false</tt> otherwise.
     */
    public boolean removeGatingSequence(Sequence sequence)
    {
        return sequencer.removeGatingSequence(sequence);
    }

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     *
     * @see SequenceBarrier
     * @param sequencesToTrack the additional sequences to track
     * @return A sequence barrier that will track the specified sequences.
     */
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return sequencer.newBarrier(sequencesToTrack);
    }

    /**
     * Get the current cursor value for the ring buffer.  The cursor value is
     * the last value that was published, or the highest available sequence
     * that can be consumed.
     */
    public final long getCursor()
    {
        return sequencer.getCursor();
    }

    /**
     * The size of the buffer.
     */
    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Given specified <tt>requiredCapacity</tt> determines if that amount of space
     * is available.  Note, you can not assume that if this method returns <tt>true</tt>
     * that a call to {@link RingBuffer#next()} will not block.  Especially true if this
     * ring buffer is set up to handle multiple producers.
     *
     * @param requiredCapacity The capacity to check for.
     * @return <tt>true</tt> If the specified <tt>requiredCapacity</tt> is available
     * <tt>false</tt> if now.
     */
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return sequencer.hasAvailableCapacity(requiredCapacity);
    }


    /**
     * Publishes an event to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.
     *
     * @param translator The user specified translation for the event
     */
    public void publishEvent(EventTranslator<E> translator)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence);
    }

    /**
     * Attempts to publish an event to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.  Will return false if specified capacity
     * was not available.
     *
     * @param translator The user specified translation for the event
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public boolean tryPublishEvent(EventTranslator<E> translator)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows one user supplied argument.
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     */
    public <A> void publishEvent(EventTranslatorOneArg<E, A> translator, A arg0)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0);
    }

    /**
     * Allows one user supplied argument.
     *
     * @see #tryPublishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A> boolean tryPublishEvent(EventTranslatorOneArg<E, A> translator, A arg0)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows two user supplied arguments.
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     */
    public <A, B> void publishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1);
    }

    /**
     * Allows two user supplied arguments.
     *
     * @see #tryPublishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A, B> boolean tryPublishEvent(EventTranslatorTwoArg<E, A, B> translator, A arg0, B arg1)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows three user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @param arg2 A user supplied argument.
     */
    public <A, B, C> void publishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, arg0, arg1, arg2);
    }

    /**
     * Allows three user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @param arg2 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A, B, C> boolean tryPublishEvent(EventTranslatorThreeArg<E, A, B, C> translator, A arg0, B arg1, C arg2)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, arg0, arg1, arg2);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows a variable number of user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param args User supplied arguments.
     */
    public void publishEvent(EventTranslatorVararg<E> translator, Object...args)
    {
        final long sequence = sequencer.next();
        translateAndPublish(translator, sequence, args);
    }

    /**
     * Allows a variable number of user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param args User supplied arguments.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public boolean tryPublishEvent(EventTranslatorVararg<E> translator, Object...args)
    {
        try
        {
            final long sequence = sequencer.tryNext();
            translateAndPublish(translator, sequence, args);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }


    /**
     * Publishes multiple events to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.
     *
     * @param translators The user specified translation for each event
     */
    public void publishEvents(EventTranslator<E>[] translators) {
        publishEvents(translators, 0, translators.length);
    }

    /**
     * Publishes multiple events to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.
     *
     * @param translators   The user specified translation for each event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch
     */
    public void publishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * Attempts to publish multiple events to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.  Will return false if specified capacity
     * was not available.
     *
     * @param translators The user specified translation for the event
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     */
    public boolean tryPublishEvents(EventTranslator<E>[] translators) {
        return tryPublishEvents(translators, 0, translators.length);
    }

    /**
     * Attempts to publish multiple events to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialised)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.  Will return false if specified capacity
     * was not available.
     *
     * @param translators   The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch
     * @return true if all the values were published, false if there was insufficient
     *         capacity.
     */
    public boolean tryPublishEvents(EventTranslator<E>[] translators, int batchStartsAt, int batchSize) {
        checkBounds(translators, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translators, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Allows one user supplied argument per event.
     *
     * @param translator The user specified translation for the event
     * @param arg0       A user supplied argument.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        publishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * Allows one user supplied argument per event.
     *
     * @param translator    The user specified translation for each event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch
     * @param arg0          An array of user supplied arguments, one element per event.
     * @see #publishEvents(EventTranslator[])
     */
    public <A> void publishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * Allows one user supplied argument.
     *
     * @param translator The user specified translation for each event
     * @param arg0       An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #tryPublishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, A[] arg0) {
        return tryPublishEvents(translator, 0, arg0.length, arg0);
    }

    /**
     * Allows one user supplied argument.
     *
     * @param translator    The user specified translation for each event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch
     * @param arg0          An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #tryPublishEvents(EventTranslator[])
     */
    public <A> boolean tryPublishEvents(EventTranslatorOneArg<E, A> translator, int batchStartsAt, int batchSize, A[] arg0) {
        checkBounds(arg0, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Allows two user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param arg0       An array of user supplied arguments, one element per event.
     * @param arg1       An array of user supplied arguments, one element per event.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A, B> void publishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1) {
        publishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * Allows two user supplied arguments per event.
     *
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch.
     * @param arg0          An array of user supplied arguments, one element per event.
     * @param arg1          An array of user supplied arguments, one element per event.
     * @see #publishEvents(EventTranslator[])
     */
    public <A, B> void publishEvents(EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1) {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * Allows two user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param arg0       An array of user supplied arguments, one element per event.
     * @param arg1       An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #tryPublishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A, B> boolean tryPublishEvents(EventTranslatorTwoArg<E, A, B> translator, A[] arg0, B[] arg1) {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1);
    }

    /**
     * Allows two user supplied arguments per event.
     *
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch.
     * @param arg0          An array of user supplied arguments, one element per event.
     * @param arg1          An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #tryPublishEvents(EventTranslator[])
     */
    public <A, B> boolean tryPublishEvents(EventTranslatorTwoArg<E, A, B> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1) {
        checkBounds(arg0, arg1, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Allows three user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param arg0       An array of user supplied arguments, one element per event.
     * @param arg1       An array of user supplied arguments, one element per event.
     * @param arg2       An array of user supplied arguments, one element per event.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A, B, C> void publishEvents(EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2) {
        publishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * Allows three user supplied arguments per event.
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The number of elements in the batch.
     * @param arg0          An array of user supplied arguments, one element per event.
     * @param arg1          An array of user supplied arguments, one element per event.
     * @param arg2          An array of user supplied arguments, one element per event.
     * @see #publishEvents(EventTranslator[])
     */
    public <A, B, C> void publishEvents(EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2) {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
    }

    /**
     * Allows three user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param arg0       An array of user supplied arguments, one element per event.
     * @param arg1       An array of user supplied arguments, one element per event.
     * @param arg2       An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public <A, B, C> boolean tryPublishEvents(EventTranslatorThreeArg<E, A, B, C> translator, A[] arg0, B[] arg1, C[] arg2) {
        return tryPublishEvents(translator, 0, arg0.length, arg0, arg1, arg2);
    }

    /**
     * Allows three user supplied arguments per event.
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch.
     * @param arg0          An array of user supplied arguments, one element per event.
     * @param arg1          An array of user supplied arguments, one element per event.
     * @param arg2          An array of user supplied arguments, one element per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #publishEvents(EventTranslator[])
     */
    public <A, B, C> boolean tryPublishEvents(EventTranslatorThreeArg<E, A, B, C> translator, int batchStartsAt, int batchSize, A[] arg0, B[] arg1, C[] arg2) {
        checkBounds(arg0, arg1, arg2, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, arg0, arg1, arg2, batchStartsAt, batchSize, finalSequence);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Allows a variable number of user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param args       User supplied arguments, one Object[] per event.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public void publishEvents(EventTranslatorVararg<E> translator, Object[]... args) {
        publishEvents(translator, 0, args.length, args);
    }

    /**
     * Allows a variable number of user supplied arguments per event.
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch
     * @param args          User supplied arguments, one Object[] per event.
     * @see #publishEvents(EventTranslator[])
     */
    public void publishEvents(EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args) {
        checkBounds(batchStartsAt, batchSize, args);
        final long finalSequence = sequencer.next(batchSize);
        translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
    }

    /**
     * Allows a variable number of user supplied arguments per event.
     *
     * @param translator The user specified translation for the event
     * @param args       User supplied arguments, one Object[] per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #publishEvents(com.lmax.disruptor.EventTranslator[])
     */
    public boolean tryPublishEvents(EventTranslatorVararg<E> translator, Object[]... args) {
        return tryPublishEvents(translator, 0, args.length, args);
    }

    /**
     * Allows a variable number of user supplied arguments per event.
     *
     * @param translator    The user specified translation for the event
     * @param batchStartsAt The first element of the array which is within the batch.
     * @param batchSize     The actual size of the batch.
     * @param args          User supplied arguments, one Object[] per event.
     * @return true if the value was published, false if there was insufficient
     *         capacity.
     * @see #publishEvents(EventTranslator[])
     */
    public boolean tryPublishEvents(EventTranslatorVararg<E> translator, int batchStartsAt, int batchSize, Object[]... args) {
        checkBounds(args, batchStartsAt, batchSize);
        try {
            final long finalSequence = sequencer.tryNext(batchSize);
            translateAndPublishBatch(translator, batchStartsAt, batchSize, finalSequence, args);
            return true;
        } catch (InsufficientCapacityException e) {
            return false;
        }
    }

    /**
     * Publish the specified sequence.  This action marks this particular
     * message as being available to be read.
     *
     * @param sequence the sequence to publish.
     */
    public void publish(long sequence)
    {
        sequencer.publish(sequence);
    }

    /**
     * Publish the specified sequences.  This action marks these particular
     * messages as being available to be read.
     *
     * @see Sequencer#next(int)
     * @param lo the lowest sequence number to be published
     * @param hi the highest sequence number to be published
     */
    public void publish(long lo, long hi)
    {
        sequencer.publish(lo, hi);
    }

    /**
     * Get the remaining capacity for this ringBuffer.
     * @return The number of slots remaining.
     */
    public long remainingCapacity()
    {
        return sequencer.remainingCapacity();
    }

    private void checkBounds(final EventTranslator<E>[] translators, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(translators, batchStartsAt, batchSize);
    }

    private void checkBatchSizing(int batchStartsAt, int batchSize) {
        if(batchStartsAt < 0 || batchSize < 0)
        {
            throw new IllegalArgumentException("Both batchStartsAt and batchSize must be positive but got: batchStartsAt " + batchStartsAt + " and bachSize " + batchSize );
        }
        else if(batchSize > bufferSize)
        {
            throw new IllegalArgumentException("The ring buffer cannot accommodate " + batchSize + " it only has space for " + bufferSize + " entities.");
        }
    }

    private <A> void checkBounds(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
    }

    private <A, B> void checkBounds(final A[] arg0, final B[] arg1, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
    }

    private <A, B, C> void checkBounds(final A[] arg0, final B[] arg1, final C[] arg2, final int batchStartsAt, final int batchSize)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(arg0, batchStartsAt, batchSize);
        batchOverRuns(arg1, batchStartsAt, batchSize);
        batchOverRuns(arg2, batchStartsAt, batchSize);
    }

    private void checkBounds(final int batchStartsAt, final int batchSize, final Object[][] args)
    {
        checkBatchSizing(batchStartsAt, batchSize);
        batchOverRuns(args, batchStartsAt, batchSize);
    }

    private <A> void batchOverRuns(final A[] arg0, final int batchStartsAt, final int batchSize)
    {
        if(batchStartsAt + batchSize > arg0.length)
        {
            throw new IllegalArgumentException("A batchSize of: " + batchSize + " with batchStatsAt of: " + batchStartsAt + " will overrun the available number of arguments: " + (arg0.length - batchStartsAt));
        }
    }

    private void translateAndPublish(EventTranslator<E> translator, long sequence)
    {
        try
        {
            translator.translateTo(get(sequence), sequence);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A> void translateAndPublish(EventTranslatorOneArg<E, A> translator, long sequence, A arg0)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B> void translateAndPublish(EventTranslatorTwoArg<E, A, B> translator, long sequence, A arg0, B arg1)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private <A, B, C> void translateAndPublish(EventTranslatorThreeArg<E, A, B, C> translator, long sequence,
                                               A arg0, B arg1, C arg2)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, arg0, arg1, arg2);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublish(EventTranslatorVararg<E> translator, long sequence, Object...args)
    {
        try
        {
            translator.translateTo(get(sequence), sequence, args);
        }
        finally
        {
            sequencer.publish(sequence);
        }
    }

    private void translateAndPublishBatch(final EventTranslator<E>[] translators, int batchStartsAt, final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                final EventTranslator<E> translator = translators[i];
                translator.translateTo(get(sequence), sequence++);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A> void translateAndPublishBatch(final EventTranslatorOneArg<E, A> translator, final A[] arg0, int batchStartsAt, final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B> void translateAndPublishBatch(final EventTranslatorTwoArg<E, A, B> translator, final A[] arg0, final B[] arg1, int batchStartsAt, int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private <A, B, C> void translateAndPublishBatch(final EventTranslatorThreeArg<E, A, B, C> translator, final A[] arg0, final B[] arg1, final C[] arg2, int batchStartsAt, final int batchSize, final long finalSequence) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, arg0[i], arg1[i], arg2[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private void translateAndPublishBatch(final EventTranslatorVararg<E> translator, int batchStartsAt, final int batchSize, final long finalSequence, final Object[][] args) {
        final long initialSequence = finalSequence - (batchSize - 1);
        try {
            long sequence = initialSequence;
            final int batchEndsAt = batchStartsAt + batchSize;
            for (int i = batchStartsAt; i < batchEndsAt; i++) {
                translator.translateTo(get(sequence), sequence++, args[i]);
            }
        } finally {
            sequencer.publish(initialSequence, finalSequence);
        }
    }

    private void fill(EventFactory<E> eventFactory)
    {
        for (int i = 0; i < entries.length; i++)
        {
            entries[i] = eventFactory.newInstance();
        }
    }
}
