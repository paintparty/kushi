(ns ^:dev/always kushi.utils
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [kushi.defs :as defs]
  ;;  [par.core :refer [? !? ?+ !?+]]
   [kushi.scales :refer [scales scaling-map]]
   [kushi.config :refer [user-config]]))

(defmacro keyed [& ks]
  #?(:clj
     `(let [keys# (quote ~ks)
            keys# (map keyword keys#)
            vals# (list ~@ks)]
        (zipmap keys# vals#))))

(defn auto-generated-hash []
  (let [rando-a-z (char (+ (rand-int 25) 97))
        hash (string/replace (str (gensym)) #"^G__" (str "_" (str rando-a-z)))]
    hash))

(defn cssfn? [x]
  (and (list? x)
       (= (first x) 'cssfn)
       (keyword? (second x))))

(declare cssfn)

(defn vec-in-cssfn [v]
  (string/join " " (map #(cond
                           (cssfn? %) (cssfn %)
                           (vector? %) (vec-in-cssfn %)
                           (keyword? %) (name %)
                           :else (str %))
                        v)))

(defn cssfn [[_ nm & args*]]
  #_(?+ "cssfn" {:nm nm :args args*})
  (let [args (map #(cond
                     (cssfn? %) (cssfn %)
                     (vector? %) (vec-in-cssfn %)
                     (keyword? %) (name %)
                     (number? %) %
                     :else (if (= nm :url)
                             (str "\"" % "\"")
                             (str %)))
                  args*)
        css-arg (string/join ", " args)]
    #_(?+ "cssfn" {:nm nm :args args* :css-arg css-arg})
    (str (name nm) "(" css-arg ")")))

(defn num->pxstr-maybe
  [prop-hydrated n]
  (let [prop-hydrated-kw (keyword prop-hydrated)]
   (if (contains? defs/int-vals prop-hydrated-kw)
    n
    (str n "px"))))

(defn convert-number
  [s hydrated-k]
  (let [float? (not (nil? (re-find #"[0-9]\.[0-9]" s)))
        s #?(:clj (if float? (. Double parseDouble s) (. Integer parseInt s))
             :cljs (if float? (js/parseFloat s) (js/parseInt s)))]
    (num->pxstr-maybe hydrated-k s)))

(defn numeric-string?
  "String representation of float or int?"
  [s]
  (when (and (string? s) (not (string/blank? s)))
    (if (re-find  #"^[-+]?[0-9]*\.?[0-9]*$" s) true false)))

(defn parse-int [s]
  #?(:clj (if float? (. Double parseDouble s) (. Integer parseInt s))
     :cljs (if float? (js/parseFloat s) (js/parseInt s))))

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

(defn css-var-string
  ([x]
   (css-var-string x nil))
  ([x suffix]
   (str "var(--" (sanitize-for-css-var-name (name x)) ")" suffix)))

(defn css-var-for-sexp [selector* css-prop]
  (let [sanitized-name (-> css-prop
                           (string/replace  #":" "_")
                           sanitize-for-css-var-name)]
    (str "--"
         selector*
         "_"
         sanitized-name)))

(defn css-var-string-!important
  [x selector* prop]
  #_(?+ "css-var-string-!important" (css-var-string (second x) "!important"))
  (if (list? (second x))
    (str "var(" (css-var-for-sexp selector* prop) ")!important")
    (css-var-string (second x) "!important")))

(defn !important-var? [x] (and (list? x) (= '!important (first x))))

(defn process-sexp [sexp selector* css-prop]
  (walk/postwalk
   (fn [x]
     (cond (cssfn? x) (cssfn x)
           (!important-var? x) (css-var-string-!important x selector* css-prop)
           :else x))
   sexp))

(defn process-value
  [v hydrated-k selector*]
   #_(? :process-value v)
   (cond
     (symbol? v)
     (str "var(--" (name v) ")")

     (and (string? v) (re-find #"[\da-z]+\*$" v))
     (let [scale-system (or (:scaling-mode user-config) :tachyons)
           scale-key (string/replace v #"\*$" "")
           css-val (get (some-> scales
                                scale-system
                                (get (hydrated-k scaling-map)))
                        scale-key nil)]
       (when css-val css-val))

     (or (numeric-string? v) (number? v))
     (convert-number (str v) hydrated-k)

     (cssfn? v)
     (cssfn v)

     (list? v)
     (process-sexp v selector* hydrated-k)

     (vector? v)
     (mapv #(process-value % hydrated-k selector*) v)

     :else v))

(defn deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? map? args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn into-coll [x]
  (if (coll? x) x [x]))

(defn starts-with-dot? [x]
 (-> x name (string/starts-with? ".")))

(defn reduce-by-pred [pred coll]
  (reduce (fn [acc v]
            (let [k (if (pred v) :valid :invalid)]
              (assoc acc k (conj (k acc) v))))
          {:valid [] :invalid []}
          coll))

(defn partition-by-pred [pred coll]
  (let [ret* (reduce (fn [acc v]
                       (let [k (if (pred v) :valid :invalid)]
                         (assoc acc k (conj (k acc) v))))
                     {:valid [] :invalid []}
                     coll)
        ret [(:valid ret*) (:invalid ret*)]]
    ret))

(defn partition-by-spec [pred coll]
  (let [ret* (reduce (fn [acc v]
                       (let [k (if (s/valid? pred v) :valid :invalid)]
                         (assoc acc k (conj (k acc) v))))
                     {:valid [] :invalid []}
                     coll)
        ret [(:valid ret*) (:invalid ret*)]]
    ret))

(defn normalized-class-kw [x]
  (if (keyword? x)
    (if (-> x name starts-with-dot?)
      (-> x name (subs 1) keyword)
      x)
    x))

(defn values? [x]
  (boolean
   (when x
     (or (string? x)
         (keyword? x)
         (when (coll? x)
           (not-empty x))))))


(defn filter-map [m pred]
  (select-keys m (for [[k v] m :when (pred k v)] k)))

(defn positions
  [pred coll]
  (keep-indexed (fn [idx x]
                  (when (pred x)
                    idx))
                coll))

(defn stacked-kw [coll]
  (when (coll? coll) (->> coll (string/join ":") keyword)))

(defn unstacked-kw [kw]
  (when (keyword? kw)
    (let [ret (-> kw name (string/split #":"))]
      (when (< 1 (count ret)) ret))))

(defn stacked-kw-tail [kw]
  (some-> kw unstacked-kw rest stacked-kw))

(defn stacked-kw-head [kw]
  (some-> kw unstacked-kw first))

(defn- merge-with-style-warning
  [v k n]
  #?(:cljs (js/console.warn
            (str
             "kushi.core/merge-with-style:\n\n "
             "The " k " value supplied in the " n " argument must be a map.\n\n "
             "You supplied:\n") v)))

(defn- bad-style? [style n]
  (let [bad? (and style (not (map? style)))]
    (when bad? (merge-with-style-warning style :style n))
    bad?))

(defn- bad-class? [class n]
  (let [bad? (and class
                  (not (some #(% class) [seq? vector? keyword? string? symbol?])))]
    (when bad? (merge-with-style-warning class :class n))
    bad?))

(defn class-coll
  [class bad-class?]
  (when-not bad-class?
    (if (or (string? class) (keyword? class))
      [class]
      class)))

(defn data-cljs [s1 s2]
  (let [coll   (remove nil? [s1 s2])
        joined (when (seq coll) (string/join " + " coll))]
    (when joined {:data-cljs joined})))

(defn on-click [c1 c2]
  (let [f (if (and c1 c2)
            (fn [e] (c1 e) (c2 e))
            (or c1 c2))]
    (when f {:on-click f})))

;; Public function for style decoration
(defn merge-with-style
  ;; TODO add docstring
  [{style1 :style class1 :class data-cljs1 :data-cljs on-click1 :on-click :as m1}
   {style2 :style class2 :class data-cljs2 :data-cljs on-click2 :on-click :as m2}]
  #_(? m1)
  (let [[bad-style1? bad-style2?] (map-indexed (fn [i x] (bad-style? x i)) [style1 style2])
        [bad-class1? bad-class2?] (map-indexed (fn [i x] (bad-class? x i)) [class1 class2])
        merged-style              (merge (when-not bad-style1? style1) (when-not bad-style2? style2))
        class1-coll               (class-coll class1 bad-class1?)
        class2-coll               (class-coll class2 bad-class2?)
        classes                   (concat class1-coll class2-coll)
        data-cljs                 (data-cljs data-cljs1 data-cljs2)
        on-click                  (on-click on-click2 on-click1)
        ret                       (assoc (merge m1 m2 data-cljs on-click)
                                         :class classes
                                         :style merged-style)]
    ret))
