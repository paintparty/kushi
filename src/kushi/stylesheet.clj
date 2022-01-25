(ns ^:dev/always kushi.stylesheet
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.data :as data]
   [garden.stylesheet]
   [garden.core :as garden]
   [kushi.config :refer [user-config user-css-file-path kushi-cache-dir kushi-cache-path version]]
   [kushi.state :as state]
   [kushi.utils :as util :refer [? keyed]]
   [kushi.atomic :as atomic]
   [kushi.reporting :as reporting]))

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

(defn append-css-chunk!
  [{:keys [css-text
           comment
           content]}]
  (let [cmnt (when comment (str "\n\n/*" comment "*/\n\n"))
        print? (and content (not (string/blank? content)))
        content (str cmnt content)]
    (when print?
     (reset! css-text (str @css-text "\n" content))) ))

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


(def license-comment-header
  (str "/*! kushi v" version " | EPL License | https://github.com/paintparty/kushi */"))


(defn append-at-font-face!
  [{:keys [css-text to-be-printed]}]
  (let [font-face-count (count @state/user-defined-font-faces)]
    (swap! to-be-printed assoc :font-face font-face-count)
    (when (pos-int? font-face-count)
      (append-css-chunk!
       {:css-text css-text
        :comment  "Font faces"
        :content  (string/join "\n" @state/user-defined-font-faces)})
      (reset! state/user-defined-font-faces []))))

(defn defkeyframe-content []
  (string/join
   "\n"
   (map (fn [[nm frames]]
          (str "@keyframes "
               (name nm)
               " {\n"
               (garden.core/css frames)
               "\n}\n"))
        @state/user-defined-keyframes)))

(defn append-defkeyframes!
  [{:keys [css-text to-be-printed]}]
  (let [keyframes-count (count @state/user-defined-keyframes)]
    (swap! to-be-printed assoc :keyframes keyframes-count)
    (when (pos-int? keyframes-count)
      (append-css-chunk!
       {:css-text css-text
        :comment  "Animation Keyframes"
        :content  (defkeyframe-content)})
      (reset! state/user-defined-keyframes {}))))

(defn count-mqs-rules [mqs]
  (count (apply concat (map #(some-> % :value :rules) mqs))))

(defn no-declarations? [coll]
  (and (vector? coll)
       (= (count coll) 2)
       (string? (first coll))
       (nil? (second coll))))

(defn append-defclasses!
  [{:keys [pretty-print? css-text to-be-printed]}]
  (when-not (empty? @state/atomic-declarative-classes-used)
    (let [gv                  (map #(let [normalized-class-kw (util/normalized-class-kw %)]
                                      (some-> @state/kushi-atomic-user-classes
                                              normalized-class-kw
                                              :garden-vecs))
                                   @state/atomic-declarative-classes-used)
          garden-vecs*        (apply concat (concat gv))
          garden-vecs         (->> garden-vecs*
                                   (remove has-mqs?)
                                   (remove no-declarations?))
          atomic-classes-mq   (atomic-classes-mq garden-vecs*)

          defclass-mq-count   (count atomic-classes-mq)
          defclass-style-rules-under-mqs (count-mqs-rules atomic-classes-mq)
          total-defclasses    (+ defclass-style-rules-under-mqs (count garden-vecs))]
      (swap! to-be-printed
             assoc
             :defclass-style-rules-under-mqs
             defclass-style-rules-under-mqs
             :defclass-style-rules
             (count garden-vecs)
             :defclass-mq-count
             defclass-mq-count)
      (append-css-chunk!
       {:css-text css-text
        :content  (garden/css {:pretty-print? pretty-print?} garden-vecs)
        :comment  "Atomic classes"})

      (when (pos-int? defclass-mq-count)
        (append-css-chunk!
         {:css-text css-text
          :content  (garden/css {:pretty-print? pretty-print?} atomic-classes-mq)
          :comment  "Atomic classes, media queries"})))
    (reset! state/kushi-atomic-user-classes atomic/kushi-atomic-combo-classes)
    (reset! state/atomic-declarative-classes-used #{})))


(defn append-rules!
  [{:keys [css-text pretty-print? to-be-printed]}]
  (let [rules                        (remove
                                      nil?
                                      (map (fn [[k v]] (when v [k v]))
                                           (:rules @state/garden-vecs-state)))
        mqs                          (remove
                                      nil?
                                      (map (fn [[k v]]
                                             (when-let [as-seq (seq v)]
                                               (apply garden.stylesheet/at-media
                                                      (cons k as-seq))))
                                           (dissoc @state/garden-vecs-state :rules)))
        garden-vecs                  (remove nil? (concat rules mqs))
        normal-style-rules-under-mqs (count-mqs-rules mqs)
        normal-style-rules           (count rules)
        normal-mq-count              (count mqs)]
    #_(util/pprint+
     {:rules                        rules
      :mqs                          mqs
      :garden-vecs                  garden-vecs
      :normal-style-rules-under-mqs normal-style-rules-under-mqs
      :normal-style-rules           normal-style-rules
      :normal-mq-count              normal-mq-count
      })
    (swap! to-be-printed
           assoc
           :normal-style-rules-under-mqs
           normal-style-rules-under-mqs
           :normal-style-rules
           normal-style-rules
           :normal-mq-count
           normal-mq-count)
    (append-css-chunk!
     {:css-text css-text
      :content  (garden/css {:pretty-print? pretty-print?} garden-vecs)
      :comment  "Component styles"})
    (reset! state/garden-vecs-state state/garden-vecs-state-init)))

(defn write-cache! [cache-is-equal?]
  (when-not cache-is-equal?
    (do
      (let [created-cache-dir? (io/make-parents kushi-cache-path)
            {fname :name
             ns*   :ns}        (meta #'write-cache!)
            nsfn               (str (ns-name ns*) "/" fname)]
        (when created-cache-dir?
          (reporting/report! nsfn (str " Created cache dir -> " kushi-cache-dir))))
      (spit kushi-cache-path @state/styles-cache-updated :append false)))
  (reset! state/styles-cache-current @state/styles-cache-updated))

(defn cache-is-equal? []
  (let [[only-in-a only-in-b _] (data/diff @state/styles-cache-current @state/styles-cache-updated)
        cache-is-equal? (and (nil? only-in-a) (nil? only-in-b))]
    cache-is-equal?))

(defn create-css-file
  {:shadow.build/stage :compile-finish}
  [build-state]
  (let [{:keys [write-stylesheet?
                __enable-caching?__
                post-build-report?]} user-config
        write-styles? (not (false? write-stylesheet?))
        pretty-print? (if (:shadow.build/mode build-state) true false)
        caching?      (true? (:__enable-caching?__ user-config))
        printables    (atom [])
        to-be-printed (atom {})
        css-text      (atom license-comment-header)
        m             {:css-text      css-text
                       :pretty-print? pretty-print?
                       :printables    printables
                       :to-be-printed to-be-printed}]

    (when-let [select-ns-msg (reporting/select-ns-msg)]
      (swap! to-be-printed assoc :select-ns-msg select-ns-msg))
    (append-at-font-face! m)
    (append-defkeyframes! m)
    (when write-styles? (append-defclasses! m))
    (when write-styles? (append-rules! m))

    (let [zero-total-rules?   (nil? (some #(not (zero? %)) (vals @to-be-printed)))
          something-to-write? (not zero-total-rules?)]

      (when (and write-stylesheet? something-to-write?)
        (use 'clojure.java.io)
        (spit user-css-file-path @css-text :append false))

      (let [cache-will-update? (when caching?
                                 (let [cache-is-equal? (cache-is-equal?)]
                                   (write-cache! cache-is-equal?)
                                   (not cache-is-equal?)))]
        #_(? "kushi.stylesheet/create-css-file: local bindings"
           (assoc
            (keyed write-styles?
                   cache-will-update?
                   post-build-report?)
            :to-be-printed @to-be-printed))

        (when (and post-build-report? something-to-write?)
          (reporting/print-report! to-be-printed cache-will-update?)))))

  ;; Must return the build state
  build-state)

(defn garden-mq-rule? [v]
  (and (map? v) (= :media (:identifier v))))
