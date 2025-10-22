(ns main
  (:gen-class)
  (:require [clojure.core.async :as async :refer [go chan <! >! timeout close! <!!]]))

(defn work-unit
  [id data]
  (let [iterations 1000
        result (reduce (fn [acc i]
                         (+ acc 
                            (Math/sin (* i data))
                            (Math/cos (/ (inc i) (inc data)))))
                       0
                       (range iterations))]
    {:id id
     :result result
     :timestamp (System/currentTimeMillis)}))

(defn spawn-worker
  [id data result-ch]
  (go
    ;;    (<! (timeout (rand-int 50)))
    (Thread/sleep 50)
    (let [result (work-unit id data)]
;;      (<! (timeout (rand-int 50)))
      (>! result-ch result))))

(defn testbed-simple
  [& {:keys [rate max-concurrent stats-interval]
      :or {rate 100
           max-concurrent 1000
           stats-interval 5000}}]
  
  (let [result-ch (chan 10000)
        control-ch (chan)
        spawn-delay (/ 1000 rate)
        stats (atom {:spawned 0
                     :completed 0
                     :active 0
                     :running true})]
    
    (go
      (loop []
        (when-let [result (<! result-ch)]
          (swap! stats (fn [s]
                         (-> s
                             (update :completed inc)
                             (update :active dec))))
          (recur))))
    
    (go
      (loop []
        (<! (timeout stats-interval))
        (let [{:keys [spawned completed active running]} @stats]
          (when running
            (let [success-rate (if (pos? spawned)
                                (float (/ completed spawned))
                                0.0)]
              (println (format "[%s] Spawned: %d | Completed: %d | Active: %d | Success: %.2f%%"
                              (java.time.LocalTime/now)
                              spawned
                              completed
                              active
                              (* 100 success-rate))))
            (recur)))))
    
    (go
      (loop [id 0]
        (when (< (:active @stats) max-concurrent)
          (spawn-worker id (rand) result-ch)
          (swap! stats (fn [s]
                         (-> s
                             (update :spawned inc)
                             (update :active inc)))))
        (<! (timeout spawn-delay))
        (recur (inc id))))
    
    (println (format "Testbed started: %d workers/sec, max %d concurrent" rate max-concurrent))
    
    control-ch))

(defn testbed-with-alts
  [& {:keys [rate max-concurrent stats-interval]
      :or {rate 100
           max-concurrent 1000
           stats-interval 5000}}]
  
  (let [result-ch (chan 10000)
        control-ch (chan)
        spawn-delay (/ 1000 rate)
        stats (atom {:spawned 0
                     :completed 0
                     :active 0
                     :running true})]
    (go
      (loop []
        (let [timeout-ch (timeout 100)
              [v ch] (async/alts! [result-ch control-ch timeout-ch])]
          (cond
            (= ch control-ch) (swap! stats assoc :running false)
            
            (= ch result-ch)
            (when v
              (swap! stats (fn [s]
                             (-> s
                                 (update :completed inc)
                                 (update :active dec))))
              (recur))
            
            :else
            (recur)))))
    
    (go
      (loop []
        (let [timeout-ch (timeout stats-interval)
              [v ch] (async/alts! [control-ch timeout-ch])]
          (if (= ch control-ch)
            (println "Stopping...")
            (do
              (let [{:keys [spawned completed active]} @stats
                    success-rate (if (pos? spawned)
                                  (float (/ completed spawned))
                                  0.0)]
                (println (format "[%s] Spawned: %d | Completed: %d | Active: %d | Success: %.2f%%"
                                (java.time.LocalTime/now)
                                spawned
                                completed
                                active
                                (* 100 success-rate))))
              (recur))))))
    
    (go
      (loop [id 0]
        (let [timeout-ch (timeout spawn-delay)
              [v ch] (async/alts! [control-ch timeout-ch])]
          (if (= ch control-ch)
            (println "Stopping...")
            (do
              (when (< (:active @stats) max-concurrent)
                (spawn-worker id (rand) result-ch)
                (swap! stats (fn [s]
                               (-> s
                                   (update :spawned inc)
                                   (update :active inc)))))
              (recur (inc id)))))))
    
    (println (format "Testbed started: %d workers/sec, max %d concurrent" rate max-concurrent))
    control-ch))

(defn flood []
  (let [c (chan)]
    (dotimes [_ 1000]
      (go (<!! c)))))

(defn testbed
  [& {:keys [mode rate max-concurrent stats-interval]
      :or {use-alts false
           rate 100
           max-concurrent 1000
           stats-interval 5000}
      :as opts}]
  (case mode
    "use-alts" (apply testbed-simple (apply concat opts))
    "flood" (dotimes [_ 10] (flood))
    (apply testbed-with-alts (apply concat opts))))

(defn stop-testbed
  [control-ch]
  (close! control-ch)
  (println "\nStopping..."))

(defn run-testbed [& opts]
  (let [control-ch (apply testbed opts)
        shutdown-promise (promise)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (stop-testbed control-ch)
                                    (deliver shutdown-promise :done))))
    @shutdown-promise))

(defn -main
  [& args]
  (let [mode (some #{"use-alts" "flood"} args)
        pid (-> (java.lang.management.ManagementFactory/getRuntimeMXBean)
                .getName
                (clojure.string/split #"@")
                first)]
    (println (str "PID: " pid))
    (println (str "Starting testbed with " mode " code"))
    (let [res (run-testbed :mode mode)]
      (println res))))

