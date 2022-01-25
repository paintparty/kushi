(ns ^:dev/always kushi.config
  (:require
   [clojure.edn :as edn]))


(def default-kushi-responsive
  [:sm {:min-width :640px}
   :md {:min-width :768px}
   :lg {:min-width :1024px}
   :xl {:min-width :1280px}
   :2xl {:min-width :1536px}])

(defn valid-responsive? [m]
  (and (map? m)
       (not-empty m)))

(defn load-edn
  "Load edn from an io/reader source (filename or io/resource)."
  [source]
  #?(:clj (do
            (use 'clojure.java.io)
            (try
              (with-open [r (clojure.java.io/reader source)]
                (edn/read (java.io.PushbackReader. r)))

              (catch java.io.IOException e
                (printf "\nCouldn't open '%s': %s.\nIgnore the above warning about 'kushi.edn' if you are running tests from the source repo (kushi/test/kushi/test.clj).\n" source (.getMessage e)))

              (catch RuntimeException e
                (printf "Error parsing edn file '%s': %s\n" source (.getMessage e)))))))

(def user-config-defaults
  {:diagnose             nil
   :diagnose-idents      nil
   :select-ns            nil
   :__enable-caching?__  false
   :post-build-report?   false
   :report-cache-update? true
   :reporting-style      :banner
   :data-attr-name       :data-cljs-source
   :ancestor             nil
   :prefix               nil
   :defclass-prefix      nil
   :keyframes-prefix     nil
   :map-mode?            false
   :css-dir              nil
   :write-stylesheet?    true
   :runtime-injection?   false
   ;; take out?
   :log-clean!?          false})

(def user-config
  (let [config*         (let [m (load-edn "kushi.edn")]
                          (if (map? m) m {}))
        user-responsive (apply array-map (:media config*))
        responsive      (if (valid-responsive? user-responsive)
                          user-responsive
                          (apply array-map default-kushi-responsive))
        ret*            (assoc config* :media responsive)
        ret             (merge user-config-defaults ret*)]
    ret))

(def user-config-args-sx-defclass
  (select-keys
   user-config
   [:data-attr-name
    :ancestor
    :prefix
    :map-mode?
    :media]))

(def user-css-file-path
  (str (or (:css-dir user-config) (:static-css-dir user-config))
       "/"
       (or (:css-filename user-config) "kushi.css")))

;; ! Update kushi version here for console printing and cache file path generation
(def version* "1.0.0-alpha")

;; You can optionally unsilence the ":LOCAL" bit when developing kushi from local filesystem (for visual feedback sanity check).
(def version (str version* ":LOCAL"))

(def kushi-cache-dir ".kushi/.cache")

(def kushi-cache-path
  (str kushi-cache-dir "/" version* ".edn"))

