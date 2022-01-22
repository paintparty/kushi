(ns ^:dev/always kushi.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.set :as set]
   [garden.core :as garden]
   [garden.def]
   [garden.stylesheet :refer [at-font-face]]
   [kushi.atomic :as atomic]
   [kushi.config :refer [user-config]]
   [kushi.parse :as parse]
   [kushi.printing :as printing]
   [kushi.selector :as selector]
   [kushi.specs :as specs]
   [kushi.state :as state]
   [kushi.stylesheet :as stylesheet]
   [kushi.typography :refer [system-font-stacks]]
   [kushi.utils :as util :refer [? keyed]]))

(def KUSHIDEBUG (atom true))

(defn kushi-debug
  {:shadow.build/stage :compile-prepare}
  [build-state]

  ;; (println {:shadow.build/stage :compile-prepare} "preparing to reset build states...")
  (state/reset-build-states!)

  ;; (util/pprint+ "After reset: kushi-debug:garden-vecs-state" @state/garden-vecs-state)
  ;; (util/pprint+ "kushi-debug:atomic-user-classes" @state/kushi-atomic-user-classes)
  ;; (util/pprint+ "kushi-debug:atomic-declarative-classes-used" @state/atomic-declarative-classes-used)
  ;; (util/pprint+ "kushi-debug:state/user-defined-keyframes" @state/user-defined-keyframes)
  ;; (util/pprint+ "kushi-debug:state/user-defined-font-faces" @state/user-defined-font-faces)

  (let [mode (:shadow.build/mode build-state)]
    #_(when mode
        (println "(:shadow.build/mode build-state) =>" mode))
    (when (not= mode :dev)
      (reset! KUSHIDEBUG false)))
  build-state)


(defn style-map->vecs
  [m]
  (when (and (:map-mode? user-config) (map? m))
    (let [->coll  #(if (coll? %) % [%])
          classes (some->> m :. ->coll (map #(->> % name (str ".") keyword)))]
      (into [] (concat classes (into [] (dissoc m :.)))))))

(defn- scoped-atomic-classname
  "Returns a classname with proper prefixing for scoping.
   Returns an uscoped classname for class not in global registry.

   Example with a user-defined class:
   (defclass myclass :c--red)
   (scoped-atomic-classname {} {:atomic :myclass}) ;=> \"_o25757__myclass\"

   Example with a kushi pre-defined class:
   (scoped-atomic-classname {} {:atomic :flex-row-c}) ;=> \"_o25757_flex-row-c\"

   Example with a normal class that has been defined in a css file or style tag:
   (scoped-atomic-classname {} {:atomic :col-2}) ;=> \".col-2\""
  [meta kw]
  (if (parse/atomic-user-class kw)
    (some-> (assoc meta :defclass-name kw :defclass-hash atomic/defclass-hash)
            selector/selector-name
            :selector*)
    kw))

(defn- atomic-classes
  [meta ks]
  (remove nil?
          (mapv (partial scoped-atomic-classname meta) ks)))

(defn- register-class!
  [classname coll*]
  (let [{:keys [selector
                selector*]} (selector/selector-name
                             {:defclass-name classname
                              :defclass-hash atomic/defclass-hash})
        hydrated-styles     (parse/with-hydrated-classes coll*)
        tokenized-styles    (mapv (partial parse/kushi-style->token selector*) hydrated-styles)
        grouped-by-mqs      (parse/grouped-by-mqs tokenized-styles)
        garden-vecs         (parse/garden-vecs grouped-by-mqs selector)]
    {:selector*       selector*
     :hydrated-styles hydrated-styles
     :garden-vecs     garden-vecs}))

(defn defclass* [sym coll* form-meta]
  (let [defclass-name            (keyword sym)
        coll                     (if (:map-mode? user-config)
                                   (-> coll* first style-map->vecs)
                                   coll*)
        {invalid-map-args
         :invalid}               (when (:map-mode? user-config) (util/reduce-by-pred map? coll*))
        {styles        :valid
         invalid-args* :invalid} (util/reduce-by-pred #(s/valid? ::specs/defclass-arg %) coll)
        invalid-args             (or
                                  (when-not (s/valid? ::specs/defclass-name sym) ^:classname [sym])
                                  (into [] (concat invalid-map-args invalid-args*)))
        {:keys [selector*
                hydrated-styles
                garden-vecs]}    (register-class! defclass-name styles)
        styles-argument-display  (if (:map-mode? user-config)
                                   coll*
                                   (apply vector coll))
        console-warning-args     {:defclass-name           defclass-name
                                  :styles-argument-display styles-argument-display
                                  :invalid-args            invalid-args
                                  :form-meta               form-meta
                                  :fname                   "defclass"}
        m                        {:n           defclass-name
                                  :selector*   selector*
                                  :args        hydrated-styles
                                  :garden-vecs garden-vecs}]

    #_(? (keyed invalid-map-args
              invalid-args
              styles
              m))

    {:defclass-name        defclass-name
     :coll                 coll
     :invalid-args         invalid-args
     :hydrated-styles      hydrated-styles
     :console-warning-args console-warning-args
     :m                    m}))

(defmacro defclass
  [sym & coll*]

  (printing/duplicate-defclass! {:fname "defclass" :nm sym :form-meta (meta &form)})
  (reset! state/current-macro :defclass)

  (let [{:keys [caching? cache-key cached]} (state/cached :defclass sym coll*)]

    #_(util/pprint+
       (str "(get\n  [:defclass\n   "
            'user-config-args-sx-defclass
            "\n   "
            sym
            "\n   "
            coll*
            "]\n   nil)")
       cached)

    (let
     [{:keys [defclass-name
              coll
              invalid-args
              console-warning-args
              m]
       :as result}    (or cached (defclass* sym coll* (meta &form)))
      js-args-warning (printing/preformat-js-warning console-warning-args)]

      ;; Print any problems to terminal

      ;; TODO why no line thing
      (printing/ansi-bad-args-warning console-warning-args)

      ;; Put atomic class into global registry
      (swap! state/kushi-atomic-user-classes assoc defclass-name m)

      (printing/diagnostics :defclass {:defclass-map m
                                       :args         coll
                                       :sym          sym})

      (when caching? (swap! state/styles-cache-updated assoc cache-key result))

      ;; Dev-only runtime code for potential warnings and dynamic injection for instant preview.
      (if @KUSHIDEBUG

        `(do
           (let [logfn# (fn [f# js-array#] (.apply js/console.warn js/console (f# js-array#)))]
             (when (seq ~invalid-args) (logfn# cljs.core/to-array ~js-args-warning)))
           nil)
        `(do nil)))))

(defmacro clean!
  "Removes all existing styles that were injected into #_kushi-dev_ style tag at dev time.
   Intended to be called by the projects main/core ns on every save/reload."
  []
  (let [log? (:log-clean!? user-config)]
    `(when ^boolean js/goog.DEBUG
       (do (when ~log? (js/console.log "kushi.core/cleaning!"))
           (let [sheet# (.-sheet (js/document.getElementById "_kushi-dev_"))
                 rules# (.-rules sheet#)
                 rules-len# (.-length rules#)]
             (clojure.core/doseq [idx# (clojure.core/reverse (clojure.core/range rules-len#))]
               (.deleteRule sheet# idx#)))))))

(defmacro add-font-face
  "Example:
   (add-font-face {:font-family \"FiraCodeBold\"
                   :font-weight \"Bold\"
                   :font-style \"Normal\"
                   :src [\"local(\\\"Fira Code Bold\\\")\"]})"
  [m]
  (let [{:keys [caching? cache-key cached]} (state/cached :add-font-face m)
        aff (or cached (garden/css (at-font-face m)))]
    (reset! state/current-macro :add-font-face)
    (swap! state/user-defined-font-faces conj aff)
    (when (and caching? (not cached))
      (swap! state/styles-cache-updated assoc cache-key aff))
    nil))


(defn system-at-font-face-rules [weights*]
  (let [weights   (if (empty? weights*)
                    system-font-stacks
                    (reduce (fn [acc v]
                              (if (contains? system-font-stacks v)
                                (assoc acc v (get system-font-stacks v))
                                acc))
                            {}
                            weights*))
        ff-rules* (for [[weight fonts-by-style] weights]
                    (for [[style fonts] fonts-by-style]
                      (garden/css
                       (at-font-face
                        {:font-family "system"
                         :font-style (name style)
                         :font-weight weight
                         :src (mapv #(str "local(\"" % "\")") fonts)}))))
        ff-rules (apply concat ff-rules*)]
    ff-rules))

(defmacro add-system-font-stack
  [& weights*]
  (let [{:keys [caching? cache-key cached]} (state/cached :system-font-stack weights*)
        ff-rules (or cached (system-at-font-face-rules weights*))]
    (doseq [rule ff-rules]
      (reset! state/current-macro :add-font-face)
      (swap! state/user-defined-font-faces conj rule))
    (when (and caching? (not cached))
      (swap! state/styles-cache-updated assoc cache-key ff-rules))
    nil))

(defn- keyframe [[k v]]
  (let [frame-key (if (vector? k)
                    (string/join ", " (map name k))
                    (string/replace (name k) #"\|" ","))
        frame-val (reduce
                   (fn [acc [key val]]
                     (assoc acc key (if (util/cssfn? val) (util/cssfn val) val)))
                   {}
                   v)]
    [frame-key frame-val]))

(defmacro defkeyframes [nm & frames*]
  (printing/duplicate-keyframes! {:fname "defkeyframes" :nm nm :form-meta (meta &form)})
  (reset! state/current-macro :defkeyframes)
  (let [{:keys [caching? cache-key cached]} (state/cached :keyframes nm frames*)
        frames (or cached (mapv keyframe frames*))]
    (swap! state/user-defined-keyframes assoc (keyword nm) frames)
    (when (and caching? (not cached))
      (swap! state/styles-cache-updated assoc cache-key frames))
    nil))

(defn cssfn [& args]
  (cons 'cssfn (list args)))

(defn- style&classes+attr [args]
  (if (:map-mode? user-config)
    (let [only-attr?       (s/valid? ::specs/map-mode-only-attr args)
          only-style?      (s/valid? ::specs/map-mode-only-style args)
          style+attr?      (s/valid? ::specs/map-mode-style+attr args)
          [style attr]     (cond only-style? [(first args) nil]
                                 style+attr? args
                                 only-attr?  [nil (first args)])
          invalid-map-args (remove nil? (map-indexed (fn [idx v] (when-not (and (map? v) (< idx 2)) v)) args))]

      #_(? "style&classes+attr"
         (keyed only-attr?
                only-style?
                style+attr?
                invalid-map-args
                style
                attr))

      {:styles+classes   style
       :attr             attr
       :invalid-map-args invalid-map-args})
    (if (map? (last args))
      {:styles+classes (drop-last args) :attr (last args)}
      {:styles+classes args :attr nil})))

(def meta-ks [:ancestor :prefix :ident :element :data-attr-name])

(defn- parse-attr+meta [args]
  (let [{styles+classes*
         :styles+classes
         attr*
         :attr
         invalid-map-args
         :invalid-map-args}        (style&classes+attr args)
        {ident        :ident
         :as          meta}        (select-keys attr* meta-ks)
        data-attr-name             (or (:data-attr-name user-config) :data-cljs)
        attr                       (apply dissoc attr* meta-ks)
        styles+classes             (if (:map-mode? user-config) (style-map->vecs styles+classes*) styles+classes*)
        {:keys [valid invalid]}    (util/reduce-by-pred #(s/valid? ::specs/kushi-arg %) styles+classes)
        {classes* :valid
         styles*  :invalid}        (util/reduce-by-pred #(s/valid? ::specs/kushi-class-like %) valid)
        {classes-with-mods :valid} (util/reduce-by-pred #(s/valid? ::specs/kushi-dot-class-with-mods %) classes*)
        classes-with-mods-hydrated (parse/with-hydrated-classes classes-with-mods)
        styles                     (into [] (concat styles* classes-with-mods-hydrated))
        invalid-args               (into [] (concat invalid-map-args invalid))]

    #_(? "parse-attr+meta"
       (keyed
        attr*
        attr
        meta
        styles+classes*
        styles+classes
        styles*
        classes*
        ident
        data-attr-name
        invalid-map-args
        invalid-args))

    {:attr           attr
     :meta           meta
     :styles+classes styles+classes
     :styles*        styles
     :classes*       classes*
     :ident          ident
     :data-attr-name data-attr-name
     :invalid-args   invalid-args}))

(defn classlist [meta classes* selector*]
  (let [non-conditional-classes                    (filter #(not (seq? %)) classes*)
        {:keys [conditional-class-sexprs classes]} (parse/parse-classes classes*)
        {atomic-class-keys :valid
         other-keys        :invalid}               (util/reduce-by-pred util/starts-with-dot? classes)
        non-conditional-atomic-class-keys          (set/intersection
                                                    (into #{} atomic-class-keys)
                                                    (into #{} non-conditional-classes))
        atomic-classes                             (atomic-classes meta (map
                                                                         util/normalized-class-kw
                                                                         non-conditional-atomic-class-keys))
        classlist                                  (concat atomic-classes [selector*] (map name other-keys))]
    {:classlist                classlist
     :atomic-class-keys        atomic-class-keys
     :conditional-class-sexprs conditional-class-sexprs}))

(defn sx* [args]
  (let [{:keys [caching? cache-key cached]} (state/cached :defclass args)]

    #_(util/pprint+
       (str "(get\n  [:sx\n   " 'user-config-args-sx-defclass "\n   "  args "]\n   nil)")
       cached)

    (or cached
        (let [{:keys [styles*
                      classes*
                      invalid-args
                      attr
                      meta
                      ident
                      data-attr-name]}     (parse-attr+meta args)
              {:keys [selector selector*]} (selector/selector-name meta)
              classlist-map                (classlist meta classes* selector*)
              styles                       (parse/+vars styles* selector*)
              css-vars                     (parse/css-vars styles* selector*)
              tokenized-styles             (mapv (partial parse/kushi-style->token selector*) styles)
              grouped-by-mqs               (parse/grouped-by-mqs tokenized-styles)
              garden-vecs                  (parse/garden-vecs grouped-by-mqs selector)
              attr-base                    (or attr {})
              ret                          (merge
                                            classlist-map
                                            {:garden-vecs    garden-vecs
                                             :attr           attr
                                             :attr-base      attr-base
                                             :css-vars       css-vars
                                             :ident          ident
                                             :invalid-args   invalid-args
                                             :data-attr-name data-attr-name
                                             :selector       selector})]
          #_(? "sx*"
             (keyed selector*
                    selector
                    classlist-map
                    styles*
                    styles
                    css-vars
                    tokenized-styles
                    grouped-by-mqs
                    garden-vecs))

          (when caching?
            (swap! state/styles-cache-updated assoc cache-key ret))
          ret))))

(defn- only-attr
  [args]
  (let [selected                (:select-ns user-config)
        select-ns-vector-valid? (s/valid? ::specs/select-ns-vector selected)]
    (when select-ns-vector-valid?
      (let [current-ns-sym       (symbol (.toString *ns*))
            current-ns?          (contains? (into #{} selected) current-ns-sym)
            current-ns-ancestor? (some? (some #(re-find
                                                (re-pattern (str %))
                                                (str current-ns-sym))
                                              selected))]
        (when-not (or current-ns-ancestor? current-ns?)
          (let [attr (or (-> args style&classes+attr :attr) {})]
            (apply dissoc attr meta-ks)))))))


(defmacro sx
  [& args]
  (reset! state/current-macro :sx)
  (reset! state/current-sx {:form-meta (meta &form) :args args :bad-mods {}})
  (or
   (only-attr args)
   (let [{:keys [atomic-class-keys
                 garden-vecs
                 attr
                 attr-base
                 classlist
                 conditional-class-sexprs
                 css-vars
                 ident
                 invalid-args
                 data-attr-name
                 selector]
          :as   m}               (sx* args)
         _                       (printing/duplicate-ident!
                                  (assoc m :form-meta (meta &form) :fname "sx"))
         styles-argument-display (apply vector args)
         compilation-warnings    (mapv (fn [v] v) @state/compilation-warnings)
         invalid-warning-args    {:invalid-args            invalid-args
                                  :styles-argument-display styles-argument-display
                                  :form-meta               (meta &form)
                                  :fname                   "sx"}
         css-injection-dev       (stylesheet/garden-vecs-injection garden-vecs)
         og-cls                  (:class attr)
         cls                     (when og-cls (if (coll? og-cls) og-cls [og-cls]))
         data-cljs               (let [{:keys [file line column]} (meta &form)]
                                   (str file ":"  line ":" column))
         js-args-warning         (printing/preformat-js-warning invalid-warning-args)]

    ;; Add classes to previously-used registry
     (doseq [kw atomic-class-keys]
       (when-not (contains? @state/atomic-declarative-classes-used kw)
         (swap! state/atomic-declarative-classes-used conj kw)
         (printing/diagnostics
          :defclass-register
          {:defclass-registered? (contains? @state/atomic-declarative-classes-used kw)})))

     (printing/diagnostics
      :sx
      (let [style*    (:style attr)
            style     (merge (when (map? style*) style*) css-vars)
            class     (distinct (concat cls classlist conditional-class-sexprs))
            data-cljs {(or data-attr-name :data-cljs) data-cljs}]
        {:ident             ident
         :garden-vecs       garden-vecs
         :css-injection-dev css-injection-dev
         :args              args
         :style-is-var?     (symbol? style)
         :attr-map          (merge attr-base {:class class :style style} data-cljs)}))

    ;; Add vecs into garden state
     (state/add-styles! garden-vecs)

     #_(? (keyed invalid-warning-args))
     (printing/ansi-bad-args-warning invalid-warning-args)
     (printing/console-warning-sx-mods)

     (reset! state/compilation-warnings [])

     (if @KUSHIDEBUG

       ;; dev builds
       `(let [og-cls#   (:class ~attr)
              cls#      (when og-cls# (if (coll? og-cls#) og-cls# [og-cls#]))
              attr-map# (merge ~attr-base
                               {:class (distinct (concat cls# (quote ~classlist) ~conditional-class-sexprs))
                                :style (merge (:style ~attr) ~css-vars)
                                :data-cljs ~data-cljs})
              logfn#    (fn [f# js-array#] (.apply js/console.warn js/console (f# js-array#)))]
          (do
            (when-not (empty? ~compilation-warnings)
              (.apply
               js/console.warn
               js/console
               (kushi.core/console-warning-number ~compilation-warnings)))

            (when (seq ~invalid-args) (logfn# cljs.core/to-array ~js-args-warning))

            (kushi.core/inject-style-rules (quote ~css-injection-dev) ~selector)
            attr-map#))

      ;; release builds
       (let
        [{:keys [only-class? only-class+style?]} (util/analyze-attr m)]
         (cond
           only-class?
           `(do {:class (quote ~classlist)})

           only-class+style?
           `(do {:class (quote ~classlist)
                 :style (merge (:style ~attr) ~css-vars)})
           :else
           `(let [og-cls#   (:class ~attr)
                  cls#      (when og-cls# (if (coll? og-cls#) og-cls# [og-cls#]))
                  attr-map# (merge ~attr-base
                                   {:class (distinct (concat cls# (quote ~classlist) ~conditional-class-sexprs))
                                    :style (merge (:style ~attr) ~css-vars)})]
              attr-map#)))))))
