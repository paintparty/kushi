(ns ^:dev/always kushi.stylesheet
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [io.aviso.ansi :as ansi]
   [garden.stylesheet]
   [garden.core :as garden]
   [kushi.config :refer [user-config]]
   [kushi.printing :refer [ansi-rainbow]]
   [kushi.state :as state]
   [kushi.specs :as specs]
   [kushi.utils :as util]))

(defn garden-vecs-injection
  [garden-vecs]
  (into []
        (map
         :rule-css
         (remove
          (fn [{x :garden-vec}] (and (vector? x) (nil? (second x))))
          (map (fn [v]
                 {:garden-vec v
                  :rule-css (garden.core/css v)})
               garden-vecs)))))

(def user-css-file-path
  (str (or (:css-dir user-config) (:static-css-dir user-config))
       "/"
       (or (:css-filename user-config) "kushi.css")))

(defn spit-css
  [{:keys [header comment garden-vecs content defclass? append pretty-print?]
    :or {append true}}]
  (use 'clojure.java.io)
  (let [cmnt (if header
               (str "/*" header "*/\n\n")
               (str (when comment (str "\n\n/*" comment "*/\n\n"))))
        path user-css-file-path
        content (str cmnt (or content (garden/css {:pretty-print? pretty-print?} garden-vecs)))]
    (if
     defclass?
      (let [file-contents (slurp path)]
        (spit path (str content "\n" file-contents)))
      (spit path content :append append))))

(defn has-mqs? [coll]
  (and (map? coll)
        (some-> coll :value :media-queries)
        coll))

(defn bunch-mqs [garden-vecs]
  (reduce (fn [acc m]
            (let [mq (-> m :value :media-queries)]
              (let [existing-rules (get acc mq)
                    rules (some-> m :value :rules)]
                (assoc acc mq (concat existing-rules rules)))))
          {}
          (filter has-mqs? garden-vecs)))

(defn atomic-classes-mq
  [garden-vecs*]
  (let [medias (-> user-config :media vals)
        mq-idx (fn [x]
                 (let [mq  (-> x :value :media-queries)
                       idx (first (keep-indexed (fn [idx v] (when (= mq v) idx)) medias))]
                   idx))
        ret*   (mapv #(let [[mq args] %]
                        (apply (partial garden.stylesheet/at-media mq) args))
                     (bunch-mqs garden-vecs*))
        ret    (sort-by mq-idx < ret*)]
    ret))

(defn print-status [n kind]
  (println (str "    " n " unique " kind)))

;; ! Update kushi version here for console printing
(def version* "1.0.0")

;; You can optionally unsilence the ":LOCAL" bit when developing kushi from local filesystem (for visual feedback sanity check).
(def version (str version* #_":LOCAL"))

(defn simple-report [selected-ns-msg printables]
  (string/join
   "\n"
   (remove nil?
           [(str "\n[kushi v" version "]")
            selected-ns-msg
            (str "Writing to " user-css-file-path " ...")
            (string/join "\n" @printables)
            "\n"])))

(defn banner-report [selected-ns-msg printables]
  (apply ansi-rainbow
         (concat
          [(str (ansi/bold (str "kushi v" version)))
           (when selected-ns-msg :br)
           selected-ns-msg
           :br
           (str "Writing to " user-css-file-path " ...")
           :br]
          @printables)))

(defn print-report! [printables]
  (let [selected         (:select-ns user-config)
        selected-ns-msg  (when (s/valid? ::specs/select-ns-vector selected)
                           (str "Compiling styles for namespaces: " selected))
        report-format-fn (if (= :banner (-> user-config :reporting-style))
                           banner-report
                           simple-report)]
    (println
     (report-format-fn selected-ns-msg printables))))

(defn write-at-font-face!
  [{:keys [pretty-print? printables]}]
  (let [font-face-count (count @state/user-defined-font-faces)]
    (when (pos-int? font-face-count)
      (do
        (swap! printables conj (str font-face-count " @font-face rule" (when (> font-face-count 1) "s")))
        (spit-css {:pretty-print? pretty-print?
                   :comment       "Font faces"
                   :content       (string/join "\n" @state/user-defined-font-faces)}))
      (reset! state/user-defined-font-faces []))))

(defn write-defkeyframes!
  [{:keys [printables]}]
  (let [keyframes-count (count @state/user-defined-keyframes)]
    (when (pos-int? keyframes-count)
      (do
        (swap! printables conj (str keyframes-count " @keyframes rule" (when (> keyframes-count 1) "s")))
        (spit-css {:comment "Animation Keyframes"
                   :content (let [content (string/join
                                           "\n"
                                           (map (fn [[nm frames]]
                                                  (str "@keyframes "
                                                       (name nm)
                                                       " {\n"
                                                       (garden.core/css frames)
                                                       "\n}\n"))
                                                @state/user-defined-keyframes))]
                              content)}))
      (reset! state/user-defined-keyframes {}))))

(defn write-defclasses!
  [{:keys [pretty-print? printables]}]
  (when-not (empty? @state/atomic-declarative-classes-used)
    (let [gv                (map #(let [normalized-class-kw (util/normalized-class-kw %)]
                                    (some-> @state/kushi-atomic-user-classes
                                            normalized-class-kw
                                            :garden-vecs))
                                 @state/atomic-declarative-classes-used)
          garden-vecs*      (apply concat (concat gv))
          garden-vecs       (remove has-mqs? garden-vecs*)
          atomic-classes-mq (atomic-classes-mq garden-vecs*)
          mq-count          (count atomic-classes-mq)]
      (swap! printables conj (str (count garden-vecs) " defclass" (when (> (count garden-vecs) 1) "es")))
      (spit-css {:pretty-print? pretty-print?
                 :garden-vecs   garden-vecs
                 :comment       "Atomic classes"})

      (when (pos-int? mq-count)
        (do
          (swap! printables conj (str mq-count " defclass" (when (> mq-count 1) "es") " under a media-query"))
          (spit-css {:pretty-print? pretty-print?
                     :garden-vecs   atomic-classes-mq
                     :comment       "Atomic classes, media queries"}))))
    (reset! state/atomic-declarative-classes-used #{})))


(defn write-rules!
  [{:keys [pretty-print? printables]}]
  (let [rules       (map (fn [[k v]] (when v [k v]))
                         (:rules @state/garden-vecs-state))
        mqs         (map (fn [[k v]]
                           (when-let [as-seq (seq v)]
                             (apply garden.stylesheet/at-media
                                    (cons k as-seq))))
                         (dissoc @state/garden-vecs-state :rules))
        garden-vecs (remove nil? (concat rules mqs))]
    (swap! printables conj (str (count garden-vecs) " class" (when (> (count garden-vecs) 1) "es")))
    (spit-css {:pretty-print? pretty-print?
               :garden-vecs   garden-vecs
               :comment       "Component styles"})

    ;; (util/pprint+ "garden-vecs-state" @state/garden-vecs-state)
    ;; (util/pprint+ "mqs" mqs)
    ;; (util/pprint+ "rules" rules)
    ;; (util/pprint+ "garden-vecs" garden-vecs)
    ))

(defn create-css-file
  {:shadow.build/stage :compile-finish}
  [build-state]
  (let [mode            (:shadow.build/mode build-state)
        pretty-print?   (if (:shadow.build/mode build-state) true false)
        printables      (atom [])
        m               {:pretty-print? pretty-print? :printables printables}]

    (use 'clojure.java.io)
    (spit-css {:header (str "! kushi v" version " | EPL License | https://github.com/paintparty/kushi ")
               :append false})

    (write-at-font-face! m)
    (write-defkeyframes! m)
    (write-defclasses! m)
    (write-rules! m)

    (print-report! printables)

    (reset! state/garden-vecs-state state/garden-vecs-state-init))

  ;; Must return the build state
  build-state)

(defn garden-mq-rule? [v]
  (and (map? v) (= :media (:identifier v))))
