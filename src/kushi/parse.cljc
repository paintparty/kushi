(ns kushi.parse
  (:require
   [clojure.string :as string]
   [garden.stylesheet]
   [clojure.spec.alpha :as s]
   [kushi.state :as state]
   [kushi.specs :as specs]
   [kushi.cssvarspecs :as cssvarspecs]
   [kushi.shorthand :as shorthand]
   [kushi.defs :as defs]
   [kushi.config :refer [user-config]]
   [kushi.utils :as util :refer [keyed]]
   [kushi.parstub :refer [? !? ?+ !?+]] ))

(defn derefed? [x]
  (s/valid? ::specs/derefed x))

(defn extract-vars* [coll]
  (mapv #(cond
           (symbol? %) %
           (vector? %) (extract-vars* %)
           :else nil)
        coll))

(defn extract-vars [selector* [css-prop val]]
  (let [!important? (and (list? val) (= '!important (first val)))
        val         (if !important? (second val) val)]
    (cond
      (symbol? val)  val
      (derefed? val) {:val-type  :derefed
                      :val       (second val)
                      :css-prop  css-prop}
      (vector? val)  (extract-vars* val)
      (list? val)    (when-not (= 'cssfn (first val))
                       {:val-type  :logic
                        :__logic   (apply list val)
                        :selector* selector*
                        :css-prop  css-prop})
      :else          nil)))

(defn sanitize-for-css-var-name [v]
  (string/escape
   v
   {\? "_QMARK"
    \! "_BANG"
    \# "_HASH"
    \+ "_PLUS"
    \$ "_DOLLAR"
    \% "_PCT"
    \= "_EQUALS"
    \< "_LT"
    \> "_GT"
    \( "_OB"
    \) "_CB"
    \& "_AMP"
    \* "_STAR"}))

(defn css-vars-map
  [extracted-vars]
  (!?+ :css-vars-map:extract-vars extracted-vars)
  (let [ret (reduce
             (fn [acc v]
               (cond
                 (and (map? v) (= :logic (:val-type v)))
                 (let [{:keys [selector*
                               __logic
                               css-prop]} v
                       k                  (util/css-var-for-sexp selector* css-prop)
                       v                  (util/process-sexp __logic selector* css-prop)]
                   (assoc acc k v))

                 (and (map? v) (= :derefed (:val-type v)))
                 (assoc acc
                        (str "--" (sanitize-for-css-var-name (:val v)))
                        (list 'clojure.core/deref (:val v)))

                 :else
                 (assoc acc
                        (str "--" (sanitize-for-css-var-name (name v)))
                        v)))
             {}
             extracted-vars)]

    (!?+ :css-vars-map:ret ret)
    ret))

(defn css-vars
  [styles selector*]
  (!?+ :css-vars:input (keyed styles selector*))
  (let [ret (some->> styles
                     (filter vector?)
                     (map (partial extract-vars selector*))
                     flatten
                     (remove nil?)
                     distinct
                     css-vars-map)]
    (!?+ :css-vars:ret ret)
    ret))


(defn atomic-user-class [k]
  (when (keyword? k)
    (get @state/kushi-atomic-user-classes k)))


(defn +vars [styles* selector*]
  (map
   (fn [v]
     (if (s/valid? ::specs/style-tuple v)
       (let [[prop val] v]
         (cond
           (derefed? val)
           [prop (util/css-var-string (second val))]

           (symbol? val)
           [prop (util/css-var-string val)]


           (list? val)
           (cond
             (= 'cssfn (first val))     [prop val]
             (util/!important-var? val) [prop (util/css-var-string-!important val selector* prop)]
             :else                      [prop (str "var(" (util/css-var-for-sexp selector* prop) ")")])

           :else
           [prop val]))
        v))
   styles*))


;; PARSING ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Nix this stuff if not needed
(def quoted-string-with-spaces "\\_")
(def quoted-string-with-spaces-regex (re-pattern (str "\\s*" quoted-string-with-spaces "\\s*")))
(defn quoted-string-with-spaces? [s] (re-find quoted-string-with-spaces-regex s))
(def multiple-css-value-separator "\\|")
(def multiple-css-value-regex (re-pattern (str "\\s*" multiple-css-value-separator "\\s*")))
(defn multiple-css-values? [s] (re-find multiple-css-value-regex  s))
(def css-value-sh-separator "\\:")
(defn css-value-shorthand? [s] (re-find #"\:" s))
(defn css-var? [s] (re-find #"^var\(--.+\)(?:!important)?$" s))

(defn warn-if-bad-number
  [prop prop-hydrated numeric-string]
  (when-not (or (= "0" numeric-string)
                (contains? defs/int-vals (keyword prop-hydrated)))
    (let [m {:warning-type   :unitless-number
             :prop           prop
             :prop-hydrated  prop-hydrated
             :numeric-string numeric-string
             :current-macro  (:fname @state/current-macro)
             :form-meta      (:form-meta @state/current-sx)}]
      #_(?+ "warn" {:m m})
      (swap! state/compilation-warnings conj m))
    ))

(defn parse-sh-value
  "Parses a shorthand css value string (which has been parsed out of an atomic keyword) into a potentially nested vector"
  [{:keys [hydrated-k k nested-shorthand?]} s]
  (or
   ;; If value is using s+ shorthand syntax with an enumerated value
   ;; Example: parse-sh-value :ta :c), which is shorthand for {:text-align :center}, return :center
   (shorthand/enum-prop-shorty (keyword hydrated-k) (keyword s))

   (cond
     (or (= "content" hydrated-k) (= "content" k) (= :content k))
     s

     ;; Parse mulitiple shorthand syntax
     ;; Example: (parse-sh-value :box-shadow "0:0:0:10:black|0:0:5:red")
     (multiple-css-values? s)
     (mapv (partial parse-sh-value {:hydrated-k hydrated-k :k k :nested-shorthand? true})
           (string/split s (re-pattern multiple-css-value-separator)))

     ;; Parse shorthand syntax into double vector
     ;; Example: (parse-sh-value :p "15px:20px") ;=> [[:15px :20px]]
     ;; Maybe check and make sure hydrated-k is not :animation?
     (css-value-shorthand? s)
     (let [ret (mapv (partial parse-sh-value {:hydrated-k hydrated-k :k k})
                     (string/split s (re-pattern css-value-sh-separator)))]
       (if nested-shorthand? ret [ret]))

     ;; If numeric, convert number to px value based on prop.
     (util/numeric-string? s)
     (do (warn-if-bad-number k hydrated-k s)
         s)

     ;; If string with spaces as underscores, convert to string with spaces.
     (and (not (css-var? s)) (quoted-string-with-spaces? s))
     (str "\"" (string/replace s #"_" " ") "\"")

     :else
     s)))

;; TODO move somewhere else?
(defn add-used-token! [v]
  (let [kw    (keyword v)
        val   (or (->> @state/alias-tokens (into {}) kw)
                  (->> @state/global-tokens (into {}) kw))
        used? (when val (->> @state/used-tokens (into {}) kw))]
    (!?+ :kushi.arguments/style-kw-with-cssvar->tuple
       {"css var from sx or defclass" kw
        "resolved value from global or alias tokens" val
        "already used?" used?})
    (!?+ (when (and val (not used?))
          (when-not used?
            (swap! state/used-tokens conj [kw val]))))))

(defn hydrate-css
  [{css-prop :css-prop val* :val :as m} selector*]
  (!?+ "hydrate-css" {:m m})
  (if (and css-prop val)
    (let [prop (shorthand/key-sh (if (string? css-prop) (keyword css-prop) css-prop))
          val  (if (or (string? val*) (keyword? val*))
                 (parse-sh-value {:hydrated-k (name prop)
                                  :k          css-prop}
                                 (name val*))
                 val*)
          val+ (util/process-value val prop selector*)]

      (!? "hydrate-css" {:val  val
                        :val+ val+})

      ;; TODO move this somewhere else
      (when (s/valid? ::cssvarspecs/wrapped-css-var-name val+)
        (add-used-token! (keyword val)))

      (assoc m :css-prop (name prop) :val val+))
    m))

(defn format-combo [s]
  (-> s
      (string/replace #"&_" " ")
      (string/replace #"&" "")
      (string/replace #"([>\+\~])" " $1 ")))

(defn format-mod
  "If mod is pseudo-class, prefixes a \":\".
   If mod is pseudo-element, prefixes a \"::\".
   If mod is combo-selector, child, or decendant, formats appropriately.
   Otherwise returns nil for dud selector."
  [mods&prop s]
  
  #_(when (= mods&prop "&[aria-expanded=\"false\"]:c")
    (?+ "format-mod" {:mods&prop mods&prop
                      :s s}))
  ;; First, extract the key by string/replacing any suffixed syntax.
  ;; Example: :nth-child(3) => :nth-child
  (let [k      (some-> s (string/replace #"\(.*\)$" "") keyword)
        has*re #"^has\((ancestor|parent)\((.+)\)\)"]
    (!?+ "format-mod" {:mods&prop mods&prop
                      :s s})
    (cond
      (contains? defs/pseudo-classes k)
      (str ":" s)

      (contains? defs/pseudo-elements k)
      (str "::" s)

      ;; combo-selector -- sibling, child, decendant, etc
      (re-find #"^[\.\>\+\~\_\&].+$" s)
      (format-combo s)

      ;;TODO remove this dark thing? (replaced by has(parent/ancestor) / .% sugar ?)
      (= s "dark")
      " _.dark_"

      ;; TODO remove this has(parent/ancestor) thing, replaced by .% sugar ?)
      ;; parent or ancestor selector via "has(parent(...))"
      (re-find has*re s)
      (let [[_ type x] (re-find has*re s)]
        {:type (keyword type) :x x})

      ;; parent selector via tokenized kw such as: "div.foo>.%:color--red"
      (re-find #"^.+ *\> *\.\%$" s)
      {:type :parent :x (string/replace s #" *\> *\.\%$" "")}

      ;; ancestors selector via tokenized kw such as: "div.foo&_.%:color--red"
      (re-find #"[^&_ ] *&_ *\.%$|[^&_ ] +\.%$" s)
      {:type :ancestors :x (string/replace s #" *&_ *\.%$| +\.%$" "")}

      ;; same element selector via tokenized kw such as: "div.foo.%:color--red"
      (re-find #"\S+\.%$" s)
      {:type :same-element :x (string/replace s #"\.%$" "")}

      :else
      ;; For reporting bad modifier to user
      (do
        #_(?+ "format-mod" s)
        (swap!
         state/current-sx
         assoc-in
         [:bad-mods mods&prop]
         (if-let [bad-mods (get (some-> @state/current-sx :bad-mods) mods&prop nil)]
           (conj bad-mods s)
           [s]))
        #_(?+ "kushi.parse/format-mod:current-sx" @state/current-macro)
        #_(?+ "kushi.parse/format-mod:current-macro" @state/current-macro)
        nil))))

(defn coll->str [coll]
 (when-not (empty? coll) (string/join coll)))

(defn mods&prop->map [mods&prop]
  (let [coll            (string/split mods&prop #":")
        prop            (last coll)
        mods*           (drop-last coll)
        mq              (when-not (empty? mods*) (specs/find-with (first mods*) specs/mq-re))
        formatted-mods* (keep (partial format-mod mods&prop) (if mq (rest mods*) mods*))
        formatted-mods  (filter string? formatted-mods*)
        prepend*        (keep #(when (map? %)
                                 (let [{:keys [x type]}   %
                                       maybe-direct-child (when (= :parent type) " >")
                                       maybe-descendant   (when-not (= :same-element type) " ")]
                                   (str x maybe-direct-child maybe-descendant)))
                              formatted-mods*)
        prepend        (coll->str prepend*)
        mods            (if prepend
                          {:prepend prepend
                           :mods     (coll->str formatted-mods)}
                          (coll->str formatted-mods))]

    #_(when true #_(state/debug?)
          (!?+ "mods&prop->map" {
                        ;; :mods&prop      mods&prop
                        ;; :mods*          mods*
                                 :mods            mods
                                 :prepend         prepend
                                 :formatted-mods  formatted-mods
                                 :formatted-mods* formatted-mods*
                                 }))
    (into {}
          (filter (comp some? val)
                  {:mods     mods
                  ;; :prepend prepend
                   :css-prop prop
                   :mq       mq}))))

;; Parsing props
(defn kushi-style->token [selector* x]
  (when (or (keyword? x) (vector? x))
    (let [[mods&prop val] (if (vector? x)
                            (let [[p v] x] [(name p) (specs/kw?->s v)])
                            (string/split (name x) #"--"))
          m2*             (mods&prop->map mods&prop)
          m2              (if val (assoc m2* :val val) m2*)
          hydrated        (hydrate-css m2 selector*)]
      #_(when true #_(state/debug?)
        (?+ "->token" (keyed mods&prop val m2* m2 hydrated)))
      hydrated)))

(defn reduce-styles
  [styles]
  (let [styles-flat (map #(-> % (select-keys [:css-prop :val]) vals vec)
                         styles)
        ret         (reduce (fn [acc [k v]]
                              (if (and k v) (assoc acc k v) acc))
                            {}
                            styles-flat)]

    #_(when (state/debug?)
      (? (keyed styles-flat ret)))

    ret))

(defn mod+styles-reducer [selector acc [mod-key* style-map]]
  (let [mod-key (if (map? mod-key*)
                  (str (:prepend mod-key*)
                        selector
                        (:mods mod-key*))
                  (str selector mod-key*))]
    #_(when (state/debug?) (? :mod-key:after mod-key))
    (conj acc [mod-key style-map])))

(defn reduce-media-queries
  [grouped selector]
   (reduce
    (fn [acc [mq-key styles+mixins]]
      (let [defaults      (when-let [defaults (:_no-mods_ styles+mixins)]
                            [selector defaults])
            mods          (reduce (partial mod+styles-reducer selector)
                                  []
                                  (dissoc styles+mixins :_no-mods_))
            mq-map        ((keyword mq-key) (:media user-config))
            at-media-args (concat [mq-map] [defaults] mods)
            at-media-rule (apply garden.stylesheet/at-media at-media-args)]
        (conj acc at-media-rule)))
    []
    (dissoc grouped :_media-default_)))

(defn kw-subs1 [x]
  (when (keyword? x)
    (-> x name (subs 1) keyword)))

(defn class->styles [x]
  (when-let [kw-no-dot (kw-subs1 x)]
    (some-> (get @state/kushi-atomic-user-classes kw-no-dot) :args)))

(defn add-mods-to-classes
  [styles mq&mods]
  ;; change to `keep`
  (remove nil?
          (map (fn [v]
                 (cond
                   (vector? v)
                   (let [[css-prop value] v] [(str mq&mods ":" (name css-prop)) value])
                   (keyword? v)
                   (keyword (str mq&mods ":" (name v)))))
               styles)))

;; TODO Write docs around this
;; Mods on classes for mixins?
(defn class-with-mods->styles [x]
  (when (s/valid? ::specs/kushi-dot-class-with-mods x)
    (let [as-string (if (vector? x) (first x) (name x))
          [mq & args] (string/split as-string #":")
          kushi-class (-> args last keyword)
          styles (some->> kushi-class keyword class->styles)
          mq&mods (string/join ":" (cons mq (drop-last args)))
          ret (add-mods-to-classes styles mq&mods)]
      ret)))

(defn with-hydrated-classes [coll]
  (reduce
   (fn [acc x]
     (if-let [rules (class->styles x)]
       (apply conj acc rules)
       (if-let [rules-w-mods (class-with-mods->styles x)]
         (apply conj acc rules-w-mods)
         (conj acc x))))
   []
   coll))

(defn valid-mod-key [mod-key*]
  #_(when true #_(state/debug?) (? mod-key*))
  (when (or (and (map? mod-key*)
                 (and (:prepend mod-key*) #_(:mods mod-key*)))
            (not (string/blank? mod-key*)))
    mod-key*))

(defn styles-by-mod-reducer
  [acc [mod-key* tokenized-styles]]
  #_(when (state/debug?)
    (? mod-key*))
  (let [mod-key (or (valid-mod-key mod-key*) :_no-mods_)]

    #_(when (state/debug?)
        (? :styles-by-mod-reducer:mod-key (keyed mod-key* mod-key)))

    (assoc acc
           mod-key
           (reduce-styles tokenized-styles))))

(defn styles-by-mq-reducer
  [acc [mq-key* tokenized-styles]]
  (let [mq-key (or mq-key* :_media-default_)]
    (assoc acc
           mq-key
           (reduce styles-by-mod-reducer
                   {}
                   (do
                     #_(when (state/debug?)
                       (? (group-by :mods (? tokenized-styles))))
                     (group-by :mods tokenized-styles))))))

(def mq-ks (->> kushi.config/user-config :media keys (map name)))

(defn grouped-and-sorted-by-mqs
  [tokenized-styles]
  (into {}
        (sort-by (fn [[k _]] (when (string? k) (.indexOf mq-ks k)))
                 (group-by :mq tokenized-styles))))

(defn grouped-by-mqs [tokenized-styles]
  #_(? (keyed tokenized-styles))
  (let [by-mqs (grouped-and-sorted-by-mqs tokenized-styles)
        grouped (reduce
                 styles-by-mq-reducer
                 {}
                 by-mqs)]
    #_(when (state/debug?)
            (? (keyed by-mqs grouped)))
    grouped))

(defn garden-vecs [grouped selector]
  (let [base [selector (-> grouped :_media-default_ :_no-mods_)]
        mods (reduce (partial mod+styles-reducer selector)
                     []
                     (-> grouped :_media-default_ (dissoc :_no-mods_)))
        mqs (reduce-media-queries grouped selector)
        ret (into [] (concat [base] mods mqs))]
    (when (state/debug?)
      #_(? (-> grouped :_media-default_ (dissoc :_no-mods_)))
      #_(? (keyed mqs))
      #_(? (keyed grouped base mods mqs ret)))
    ret))

