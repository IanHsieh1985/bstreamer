package me.vzhilin.bstreamer.server.media.impl;

import me.vzhilin.bstreamer.server.ServerContext;
import me.vzhilin.bstreamer.server.SourceKey;
import me.vzhilin.bstreamer.server.scheduler.BufferingLimits;
import me.vzhilin.bstreamer.server.scheduler.PushSource;
import me.vzhilin.bstreamer.server.streaming.base.PullSource;
import me.vzhilin.bstreamer.util.PropertyMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PullSourceRegistry {
    private final static String DEFAULT_PACKAGE = "me.vzhilin.bstreamer.server.streaming.";
    private final Map<SourceKey, PushSource> sources = new HashMap<>();
    private final Function<SourceKey, PushSource> mappingFunction;
    private final ServerContext serverContext;

    public PullSourceRegistry(ServerContext context, BufferingLimits limits, ScheduledExecutorService workers) {
        this.serverContext = context;
        mappingFunction = (SourceKey key) -> new PushSource(supplierFor(key), key.cfg, workers, limits);
    }

    public PushSource get(SourceKey key) {
        return sources.computeIfAbsent(key, mappingFunction);
    }

    private Supplier<PullSource> supplierFor(SourceKey key) {
        try {
            Class<PullSource> pullSource = (Class<PullSource>) Class.forName(DEFAULT_PACKAGE + key.clazz);
            Constructor<PullSource> constructor = pullSource.getDeclaredConstructor(ServerContext.class, PropertyMap.class);
            return () -> {
                try {
                    return constructor.newInstance(serverContext, key.cfg);
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            };
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
