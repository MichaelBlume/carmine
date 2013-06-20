(ns taoensso.carmine.protocol
  "Facilities for actually communicating with Redis server using its
  request/response protocol. Originally adapted from Accession.

  Ref: http://redis.io/topics/protocol"
  {:author "Peter Taoussanis"}
  (:require [clojure.string         :as str]
            [taoensso.carmine.utils :as utils]
            [taoensso.nippy.tools   :as nippy-tools])
  (:import  [java.io DataInputStream BufferedOutputStream]))

;; Hack to allow cleaner separation of namespaces
(utils/declare-remote taoensso.carmine.connections/get-spec
                      taoensso.carmine.connections/in-stream
                      taoensso.carmine.connections/out-stream)

(defrecord Context [in-stream out-stream parser-queue])
(def ^:dynamic *context* nil)
(def ^:dynamic *parser*  nil)
(def ^:private no-context-error
  (Exception. (str "Redis commands must be called within the context of a"
                   " connection to Redis server. See `wcar`.")))

(defrecord WrappedRaw [ba])
(defn raw "Forces byte[] argument to be sent to Redis as raw, unencoded bytes."
  [x] (if (utils/bytes? x) (WrappedRaw. x)
          (throw (Exception. "Raw arg must be byte[]"))))

(defn bytestring
  "Redis communicates with clients using a (binary-safe) byte string protocol.
  This is the equivalent of the byte array representation of a Java String."
  ^bytes [^String s] (.getBytes s "UTF-8"))

;;; Request delimiters
(def ^bytes   bs-crlf (bytestring "\r\n"))
(def ^Integer bs-*    (int (first (bytestring "*"))))
(def ^Integer bs-$    (int (first (bytestring "$"))))

;; Carmine-only markers that'll be used _within_ bulk data to indicate that
;; the data requires special reply handling
(def ^bytes bs-bin     (bytestring "\u0000<")) ; Binary data marker
(def ^bytes bs-clj     (bytestring "\u0000>")) ; Frozen data marker

;;; Fns to actually send data to stream buffer
(defn send-crlf [^BufferedOutputStream out] (.write out bs-crlf 0 2))
(defn send-bin  [^BufferedOutputStream out] (.write out bs-bin  0 2))
(defn send-clj  [^BufferedOutputStream out] (.write out bs-clj  0 2))
(defn send-*    [^BufferedOutputStream out] (.write out bs-*))
(defn send-$    [^BufferedOutputStream out] (.write out bs-$))
(defn send-arg
  "Send arbitrary argument along with information about its size:
  $<size of arg> crlf
  <arg data>     crlf

  Argument type will determine how it'll be stored with Redis:
    * String args become byte strings.
    * Simple numbers (integers, longs, floats, doubles) become byte strings.
    * Binary (byte array) args go through un-munged.
    * Everything else gets serialized."
  [^BufferedOutputStream out arg]
  (let [type (cond (string?      arg) :string ; Most common 1st!
                   (keyword?     arg) :keyword
                   (utils/bytes? arg) :bytes
                   (or (instance? Long    arg)
                       (instance? Double  arg)
                       (instance? Integer arg)
                       (instance? Float   arg)) :simple-num
                   (instance? WrappedRaw  arg)  :raw
                   :else                        :frozen)

        ^bytes ba (case type
                    :string     (bytestring arg)
                    :keyword    (bytestring (utils/fq-name arg))
                    :simple-num (bytestring (str arg))
                    :bytes      arg
                    :raw        (:ba arg)
                    :frozen     (nippy-tools/freeze arg))

        payload-size (alength ba)
        marked-type? (case type (:bytes :frozen) true false)
        data-size    (if marked-type? (+ payload-size 2) payload-size)]

    ;; To support various special goodies like serialization, we reserve
    ;; strings that begin with a null terminator
    (when-let [s (case type :string arg :keyword (name arg) nil)]
      (when (.startsWith ^String s "\u0000")
        (throw (Exception. (str "String args can't start with the null terminator: "
                                arg)))))

    (send-$ out) (.write out (bytestring (str data-size))) (send-crlf out)
    (when marked-type?
      (case type
        :bytes  (send-bin out)
        :frozen (send-clj out)))
    (.write out ba 0 payload-size) (send-crlf out)))

(defn send-request
  "Sends a command to Redis server using its byte string protocol:
      *<no. of args>     crlf
      [$<size of arg N>  crlf
        <arg data>       crlf ...]"
  [args]
  (let [^BufferedOutputStream out (or (:out-stream *context*)
                                      (throw no-context-error))]

    (send-* out)
    (.write out (bytestring (str (count args))))
    (send-crlf out)

    (doseq [arg args] (send-arg out arg))
    (.flush out)

    (when-let [pq (:parser-queue *context*)] (swap! pq conj *parser*))))

(defn get-basic-reply
  "BLOCKS to receive a single reply from Redis server. Applies basic parsing
  and returns the result.

  Redis will reply to commands with different kinds of replies, identified by
  their first byte:
      * `+` for single line reply.
      * `-` for error message.
      * `:` for integer reply.
      * `$` for bulk reply.
      * `*` for multi bulk reply."
  [^DataInputStream in & [raw?]]
  (let [reply-type (char (.readByte in))]
    (case reply-type
      \+ (.readLine in)
      \- (Exception.     (.readLine in))
      \: (Long/parseLong (.readLine in))

      ;; Bulk replies need checking for special in-data markers
      \$ (let [data-size (Integer/parseInt (.readLine in))]
           (when-not (neg? data-size) ; NULL bulk reply
             (let [maybe-marked-type? (and (not raw?) (>= data-size 2))
                   type (or (when maybe-marked-type?
                              (.mark in 2)
                              (let [h (byte-array 2)]
                                (.readFully in h 0 2)
                                (condp utils/ba= h
                                  bs-clj :clj
                                  bs-bin :bin
                                  nil)))
                            (if raw? :raw :str))

                   marked-type? (case type (:clj :bin) true false)
                   payload-size (int (if marked-type? (- data-size 2) data-size))
                   payload      (byte-array payload-size)]

               (when (and maybe-marked-type? (not marked-type?)) (.reset in))
               (.readFully in payload 0 payload-size)
               (.readFully in (byte-array 2) 0 2) ; Discard final crlf

               (try
                 (case type
                   :str (String. payload 0 payload-size "UTF-8")
                   :clj (nippy-tools/thaw payload)
                   (:bin :raw) payload)
                 (catch Exception e
                   (Exception. (str "Bad reply data: " (.getMessage e)) e))))))

      \* (let [bulk-count (Integer/parseInt (.readLine in))]
           (utils/repeatedly* bulk-count #(get-basic-reply in raw?)))
      (throw (Exception. (str "Server returned unknown reply type: "
                              reply-type))))))

(defn- get-parsed-reply [^DataInputStream in parser]
  (if-not parser
    (get-basic-reply in)
    (let [{:keys [dummy-reply? raw?] :as m} (meta parser)]
      (let [reply (when-not dummy-reply? (get-basic-reply in raw?))]
        (try (parser reply)
             (catch Exception e
               (Exception. (str "Parser error: " (.getMessage e)) e)))))))

(defn get-replies
  "Implementation detail - don't use this.
  BLOCKS to receive queued (pipelined) replies from Redis server. Applies all
  parsing and returns the result. Note that Redis returns replies as a FIFO
  queue per connection."
  [& [as-pipeline?]]
  (let [^DataInputStream in (or (:in-stream *context*)
                                (throw no-context-error))
        parsers     @(:parser-queue *context*)
        reply-count (count parsers)]

    (when (pos? reply-count)
      ;; (swap! (:parser-queue *context*) #(subvec % reply-count))
      (reset! (:parser-queue *context*) [])

      (if (or (> reply-count 1) as-pipeline?)
        (mapv #(get-parsed-reply in %) parsers)
        (let [reply (get-parsed-reply in (nth parsers 0))]
          (if (instance? Exception reply) (throw reply) reply))))))

(defmacro with-context
  "Evaluates body in the context of a thread-bound connection to a Redis server.
  For non-listener connections, returns server's response."
  [connection & body]
  `(let [listener?# (:listener? (taoensso.carmine.connections/get-spec
                                 ~connection))]
     (binding [*context*
               (Context. (taoensso.carmine.connections/in-stream  ~connection)
                         (taoensso.carmine.connections/out-stream ~connection)
                         (when-not listener?# (atom [])))
               *parser* nil]
       ~@body
       (when-not listener?# (get-replies)))))