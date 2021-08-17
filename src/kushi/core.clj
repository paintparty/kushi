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
   [kushi.utils :as util]))


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
  (let [hydrated-styles (parse/with-hydrated-classes coll*)
        tokenized-styles (mapv parse/kushi-style->token hydrated-styles)
        grouped-by-mqs (parse/grouped-by-mqs tokenized-styles)
        {:keys [selector
                selector*]} (selector/selector-name
                             {:defclass-name classname
                              :defclass-hash atomic/defclass-hash})
        garden-vecs (parse/garden-vecs grouped-by-mqs selector)]
    {:selector* selector*
     :hydrated-styles hydrated-styles
     :garden-vecs garden-vecs}))

(defmacro defclass
  [sym & coll]
  (reset! state/current-macro :defclass)
  (let [defclass-name (keyword sym)
        {styles :valid invalid-args* :invalid} (util/reduce-by-pred #(s/valid? ::specs/defclass-arg %) coll)
        invalid-args (or
                      (when-not (s/valid? ::specs/defclass-name sym) ^:classname [sym])
                      (into [] invalid-args*))
        {:keys [selector* hydrated-styles garden-vecs]} (register-class! defclass-name styles)
        styles-argument-display (apply vector coll)
        console-warning-args {:defclass-name defclass-name
                              :styles-argument-display styles-argument-display
                              :invalid-args invalid-args}
        m {:n defclass-name
           :selector* selector*
           :args hydrated-styles
           :garden-vecs garden-vecs}]

    ;; Print any problems to terminal
    (printing/console-warning-defclass console-warning-args)

    ;; Put atomic class into global registry
    (swap! state/kushi-atomic-user-classes assoc defclass-name m)

    (printing/diagnostics :defclass {:defclass-map m :args coll :sym sym})

    ;; Dev-only runtime code for potential warnings and dynamic injection for instant preview.
    `(do
       (when ^boolean js/goog.DEBUG
         (when (seq ~invalid-args)
           (do
             (.apply
              js/console.warn
              js/console
              (kushi.core/js-warning-defclass ~console-warning-args)))))
       nil)))


(defmacro add-font-face
  "Example:
   (add-font-face {:font-family \"FiraCodeBold\"
                   :font-weight \"Bold\"
                   :font-style \"Normal\"
                   :src [\"local(\"Fira Code Bold\")\"]})"
  [m]
  (reset! state/current-macro :add-font-face)
  (swap! state/user-defined-font-faces
         conj
         (garden/css (at-font-face m))))


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


(defmacro defkeyframes [k & frames*]
  (reset! state/current-macro :defkeyframes)
  (let [frames (mapv keyframe frames*)]
    (swap! state/user-defined-keyframes assoc k frames)))



(defn cssfn [& args]
  (cons 'cssfn (list args)))



(defn- parse-attr+meta [args]
  (let [attr* (when (map? (last args)) (last args))
        meta-ks [:parent :prefix :f :ident :element :data-ns]
        {:keys [f ident] :as meta} (select-keys attr* meta-ks)
        data-ns-key (or (:data-ns-key user-config) :data-ns)
        attr (apply dissoc attr* meta-ks)
        styles+classes (if attr* (drop-last args) args)
        {:keys [valid invalid]} (util/reduce-by-pred #(s/valid? ::specs/kushi-arg %) styles+classes)
        {classes* :valid styles* :invalid} (util/reduce-by-pred #(s/valid? ::specs/kushi-class-like %) valid)
        {classes-with-mods :valid} (util/reduce-by-pred #(s/valid? ::specs/kushi-dot-class-with-mods %) classes*)
        classes-with-mods-hydrated (parse/with-hydrated-classes classes-with-mods)
        styles (into [] (concat styles* classes-with-mods-hydrated))]
    {:attr attr
     :meta meta
     :styles+classes styles+classes
     :styles* styles
     :classes* classes*
     :f f
     :ident ident
     :data-ns-key data-ns-key
     :invalid-args invalid}))

(defn classlist [meta classes* selector*]
  (let [non-conditional-classes (filter #(not (seq? %)) classes*)
        {:keys [conditional-class-sexprs classes]} (parse/parse-classes classes*)
        {atomic-class-keys :valid other-keys :invalid} (util/reduce-by-pred util/starts-with-dot? classes)
        non-conditional-atomic-class-keys (set/intersection
                                           (into #{} atomic-class-keys)
                                           (into #{} non-conditional-classes))
        atomic-classes (atomic-classes meta (map
                                             util/normalized-class-kw
                                             non-conditional-atomic-class-keys))
        classlist (concat atomic-classes [selector*] (map name other-keys))]
   {:classlist classlist
    :atomic-class-keys atomic-class-keys
    :conditional-class-sexprs conditional-class-sexprs}))

(defn sx* [args]
  (let [{:keys [styles* classes* invalid-args attr meta ident f data-ns-key]} (parse-attr+meta args)
        {:keys [selector selector*]} (selector/selector-name meta)
        classlist-map (classlist meta classes* selector*)
        styles (parse/+vars styles* selector*)
        css-vars (parse/css-vars styles* selector*)
        tokenized-styles (mapv parse/kushi-style->token styles)
        grouped-by-mqs (parse/grouped-by-mqs tokenized-styles)
        garden-vecs (parse/garden-vecs grouped-by-mqs selector)
        attr-base (or attr {})]

    (merge
     classlist-map
     {:garden-vecs garden-vecs
      :attr attr
      :attr-base attr-base
      :css-vars css-vars
      :f f
      :ident ident
      :invalid-args invalid-args
      :data-ns-key data-ns-key
      :selector selector})))

(defmacro sx
  "Receives n-number of args representing css style declarations or classes.
   Evaluates styles and adds css data to global registery along with auto-generated
   (or user-defined) selector prefix.  A optional map containing html attributes
   and/or kushi-specific options can be passed as the last argument.
   Returns a normalized attribute map which incorporates the prefixed selector(s)
   into the :class attribute."
  [& args]
  #_(println "(sx" (string/join " " (drop 2 args))  " ...)")
  (reset! state/current-macro :sx)
  (let [{:keys [atomic-class-keys
                garden-vecs
                attr
                attr-base
                classlist
                conditional-class-sexprs
                css-vars
                f
                ident
                invalid-args
                data-ns-key
                selector]} (sx* args)
        styles-argument-display (apply vector args)
        compilation-warnings (mapv (fn [v] v) @state/compilation-warnings)
        invalid-warning-args {:invalid-args invalid-args
                              :styles-argument-display styles-argument-display}
        css-injection-dev (stylesheet/garden-vecs-injection garden-vecs)
        og-cls (:class attr)
        cls (when og-cls (if (coll? og-cls) og-cls [og-cls]))]

    ;; Add classes to previously-used registry
    (doseq [kw atomic-class-keys]
      (when-not (contains? @state/atomic-declarative-classes-used kw)
        (swap! state/atomic-declarative-classes-used conj kw)
        (printing/diagnostics
         :defclass-register
         {:defclass-registered? (contains? @state/atomic-declarative-classes-used kw)})))

    (printing/diagnostics
     :sx
     {:ident ident
      :garden-vecs garden-vecs
      :css-injection-dev css-injection-dev
      :args args
      :attr-map (merge attr-base
                       {:class (distinct (concat cls classlist conditional-class-sexprs))
                        :style (merge (:style attr) css-vars)})})

    ;; Add vecs into garden state
    (state/add-styles! garden-vecs)

    (printing/console-warning-sx invalid-warning-args)

    (reset! state/compilation-warnings [])

    `(let [og-cls# (:class ~attr)
           cls# (when og-cls# (if (coll? og-cls#) og-cls# [og-cls#]))
           attr-map# (merge ~attr-base
                            {:class (distinct (concat cls# (quote ~classlist) ~conditional-class-sexprs))
                             :style (merge (:style ~attr) ~css-vars)})]
       (if ^boolean js/goog.DEBUG
         (do
           (when-not (empty? ~compilation-warnings)
             (.apply
              js/console.warn
              js/console
              (kushi.core/console-warning-number ~compilation-warnings)))

           (when (seq ~invalid-args)
             (do
               (.apply
                js/console.warn
                js/console
                (kushi.core/js-warning-sx ~invalid-warning-args))))
           (kushi.core/inject-style-rules (quote ~css-injection-dev) ~selector)
           (merge (when ~f {~data-ns-key (kushi.core/ns+ ~f ~ident)})
                  attr-map#))
         attr-map#))))