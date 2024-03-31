/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

public class BufferingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BufferingManager.class);

    public interface Callback {
        void onReleased(ChannelHandlerContext context, Position position);
    }

    private static class Holder implements Comparable<Holder> {

        private static final Comparator<Position> COMPARATOR = Comparator
                .comparing(Position::getFixTime)
                .thenComparing(Position::getDeviceTime)
                .thenComparing(Position::getServerTime);

        private final ChannelHandlerContext context;
        private final Position position;
        private Timeout timeout;

        private Holder(ChannelHandlerContext context, Position position) {
            this.context = context;
            this.position = position;
        }

        @Override
        public int compareTo(Holder other) {
            return COMPARATOR.compare(position, other.position);
        }
    }

    private final Timer timer = new HashedWheelTimer();
    private final Callback callback;
    private final long threshold;

    private final Map<Long, TreeSet<Holder>> buffer = new HashMap<>();

    public BufferingManager(Config config, Callback callback) {
        this.callback = callback;
        threshold = config.getLong(Keys.SERVER_BUFFERING_THRESHOLD);
    }

    private Timeout scheduleTimeout(Holder holder) {
        return timer.newTimeout(
                timeout -> {
                    LOGGER.info("released {}", holder.position.getFixTime());
                    buffer.get(holder.position.getDeviceId()).remove(holder);
                    callback.onReleased(holder.context, holder.position);
                },
                threshold, TimeUnit.MILLISECONDS);
    }

    public void accept(ChannelHandlerContext context, Position position) {
        if (threshold > 0) {
            synchronized (buffer) {
                LOGGER.info("queued {}", position.getFixTime());
                var queue = buffer.computeIfAbsent(position.getDeviceId(), k -> new TreeSet<>());
                Holder holder = new Holder(context, position);
                holder.timeout = scheduleTimeout(holder);
                queue.add(holder);
                queue.tailSet(holder).forEach(h -> {
                    h.timeout.cancel();
                    h.timeout = scheduleTimeout(h);
                });
            }
        } else {
            callback.onReleased(context, position);
        }
    }

}
