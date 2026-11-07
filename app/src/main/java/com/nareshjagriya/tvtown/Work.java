package com.nareshjagriya.tvtown;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Background work: pools whose threads go away after 20 idle seconds, so an idle TV Town holds none. */
final class Work {

    private Work() {
    }

    /** At most n tasks at once; further tasks wait their turn. */
    static ExecutorService pool(int n) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(n, n, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        p.allowCoreThreadTimeOut(true);
        return p;
    }
}
