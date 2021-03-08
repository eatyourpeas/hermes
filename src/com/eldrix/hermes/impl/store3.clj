(ns com.eldrix.hermes.impl.store3
  (:require [clojure.core.async :as a]
            [com.eldrix.hermes.impl.ser :as ser]
            [taoensso.nippy :as nippy]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.time LocalDate)
           (org.lmdbjava EnvFlags Env DbiFlags Dbi PutFlags CopyFlags GetOp ByteBufProxy Cursor Txn)
           (java.io Closeable DataOutput DataInput)
           (com.eldrix.hermes.snomed Concept)
           (io.netty.buffer ByteBufAllocator ByteBufOutputStream ByteBuf ByteBufInputStream)
           (java.util UUID)))

;; The LMDB key value store persists optimised SNOMED data using either
;; a simple key-value (such as concept-id to concept) or a compound key
;; with no value (such as the database holding parent and child relationships.
(deftype LmdbStore
  [^Env env
   ;;;;;BUCKET;;;;;;;;;                 ;                   ;; [key] [value]
   ^Dbi concepts                                            ;; [concept-id]   [concept]
   ^Dbi descriptions                                        ;; [concept-id -- description-id] [description]
   ^Dbi relationships                                       ;; [relationshipId]  [relationship]
   ^Dbi refsetItems                                         ;; [refset-item-id] [refset]
   ;;;;;INDEX;;;;;;;;;                                      ;; [key] [value]
   ^Dbi descriptionsConcept                                 ;; [description-id] [concept-id]
   ^Dbi parentRelationships                                 ;; [sourceId -- typeId -- group ] [destinationId]
   ^Dbi childRelationships                                  ;; [destinationId -- typeId -- group ] [sourceId]
   ^Dbi installedRefsets                                    ;; [refsetId]  [true]
   ^Dbi componentRefsets                                    ;; [referenced-component-id -- refset-id] [refset-item-id]
   ^Dbi mapTargetComponent                                  ;; [refsetId -- mapTarget] [refset-item-id]
   ]
  Closeable
  (close [_this] (.close env)))

(defn open-store [path {:keys [read-only?]
                        :or   {read-only? true}}]
  (let [env-builder (doto (Env/create ByteBufProxy/PROXY_NETTY)
                      (.setMapSize (* 5 1024 1024 1024))    ;; max size 5Gb for starters
                      (.setMaxDbs 10))
        env (.open env-builder path (into-array EnvFlags (cond-> [EnvFlags/MDB_NOTLS]
                                                                 read-only? (conj EnvFlags/MDB_RDONLY_ENV))))
        base-flags (into-array DbiFlags (if read-only? [] [DbiFlags/MDB_CREATE]))
        concepts (.openDbi env "con" base-flags)
        descriptions (.openDbi env "des" base-flags)
        descriptions-concept (.openDbi env "dec" base-flags)
        relationships (.openDbi env "rel" base-flags)
        parent-rels (.openDbi env "par" base-flags)
        child-rels (.openDbi env "chr" base-flags)
        refset-items (.openDbi env "rfi" base-flags)
        installed-refsets (.openDbi env "ins" base-flags)
        component-refsets (.openDbi env "crs" base-flags)
        map-target-component (.openDbi env "mtc" base-flags)]
    (->LmdbStore
      env concepts descriptions descriptions-concept relationships
      parent-rels child-rels refset-items installed-refsets component-refsets
      map-target-component)))

(defn- should-write?
  "Determine whether to write an object into a bucket.
  This only writes the object if the effectiveTime of the entity is newer than
  the version that already exists. LMDB writes are single-threaded.
  Optimised so avoids deserialising anything except the effective time from
  the stored component, if found."
  [^Dbi bucket ^Txn txn ^ByteBuf kbb read-offset ^LocalDate effectiveTime]
  (let [existing (.get bucket txn kbb)]
    (or (not existing) (.isAfter effectiveTime (ser/read-effective-time (ByteBufInputStream. existing) read-offset)))))

(defn put-concepts
  [^LmdbStore store objects]
  (let [kbb (.buffer ByteBufAllocator/DEFAULT 8)
        vbb (.buffer ByteBufAllocator/DEFAULT 128)
        out (ByteBufOutputStream. vbb)
        put-flags (make-array PutFlags 0)]
    (with-open [txn (.txnWrite ^Env (.-env store))]
      (doseq [o objects]
        (.writeLong kbb (:id o))
        (when (should-write? (.-concepts store) txn kbb 8 (:effectiveTime o))
          (ser/write-concept out o)
          (.put ^Dbi (.-concepts store) txn kbb vbb put-flags)
          (.clear vbb))
        (.clear kbb))
      (.commit txn))
    (.release kbb) (.release vbb)))

(defn put-descriptions
  [^LmdbStore store objects]
  (let [kbb1 (.buffer ByteBufAllocator/DEFAULT 16)          ;; [concept-id description-id] = [description]
        kbb2 (.buffer ByteBufAllocator/DEFAULT 8)           ;; [description-id]  = [concept-id]
        vbb1 (.buffer ByteBufAllocator/DEFAULT 256)
        vbb2 (.buffer ByteBufAllocator/DEFAULT 8)
        out (ByteBufOutputStream. vbb1)
        put-flags (make-array PutFlags 0)]
    (with-open [txn (.txnWrite ^Env (.-env store))]
      (doseq [o objects]
        (.writeLong kbb1 (:conceptId o))
        (.writeLong kbb1 (:id o))
        (when (should-write? (.-descriptions store) txn kbb1 8 (:effectiveTime o))
          (ser/write-description out o)
          (.put ^Dbi (.-descriptions store) txn kbb1 vbb1 put-flags)
          (.writeLong kbb2 (:id o))
          (.writeLong vbb2 (:conceptId o))
          (.put ^Dbi (.-descriptionsConcept store) txn kbb2 vbb2 put-flags)
          (.clear vbb1) (.clear kbb2) (.clear vbb2))
        (.clear kbb1))
      (.commit txn))
    (.release kbb1) (.release vbb1) (.release kbb2) (.release vbb2)))

(defn put-relationships
  [^LmdbStore store objects])

(defn put-refset-items
  "Put the refset items into the backing store.
  Parameters
  - env      : LMDB environment
  - bucket   : LMDB database
  - objects  : objects to write
  - header   : header to write *before* data written (single 8-bit integer)
  - write-fn : function to write data from object to DataOutput
             : should take two parameters : [^DataOutput out ^Object o]."
  [^Env env ^Dbi bucket objects header write-fn]
  (let [kbb (.buffer ByteBufAllocator/DEFAULT 16)
        vbb (.buffer ByteBufAllocator/DEFAULT 1024)
        out (ByteBufOutputStream. vbb)
        put-flags (make-array PutFlags 0)]
    (with-open [txn (.txnWrite env)]
      (doseq [o objects]
        (let [uuid ^UUID (:id o)]
          (.writeLong kbb (.getMostSignificantBits uuid))
          (.writeLong kbb (.getLeastSignificantBits uuid)))
        (when (should-write? bucket txn kbb 17 (:effectiveTime o)) ;; 1 byte header, 16 byte identifier
          (.writeByte out ^int header)
          (write-fn out o)
          (.put bucket txn kbb vbb put-flags)
          (.clear vbb))
        (.clear kbb))
      (.commit txn))
    (.release kbb) (.release vbb)))

(defn get-concept [^LmdbStore store ^long id]
  (with-open [txn (.txnRead ^Env (.env store))]
    (let [kbb (.buffer ByteBufAllocator/DEFAULT 8)]
      (.writeLong kbb id)
      (when-let [vbb ^ByteBuf (.get ^Dbi (.concepts store) txn kbb)]
        (let [in (ByteBufInputStream. vbb)]
          (ser/read-concept in))))))

(defn get-description
  "Returns the description with the `id` specified."
  [^LmdbStore store ^long id]
  (with-open [txn (.txnRead ^Env (.env store))]
    (let [kbb (.buffer ByteBufAllocator/DEFAULT 16)]
      (try
        (.writeLong kbb id)
        (when-let [concept-id-bb ^ByteBuf (.get ^Dbi (.-descriptionsConcept store) txn kbb)]
          (.clear kbb)
          (.writeLong kbb (.readLong concept-id-bb))
          (.writeLong kbb id)
          (when-let [description-bb (.get ^Dbi (.-descriptions store) txn kbb)]
            (ser/read-description (ByteBufInputStream. description-bb))))
        (finally (.release kbb))))))

(defmulti write-batch :type)

(defmethod write-batch :info.snomed/Concept [batch store]
  (put-concepts store (:data batch)))

(defmethod write-batch :info.snomed/Description [batch store]
  (put-descriptions store (:data batch)))

(defmethod write-batch :info.snomed/Relationship [batch store]
  (put-relationships store (:data batch)))

(defmethod write-batch :info.snomed/SimpleRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 1 ser/write-simple-refset-item))

(defmethod write-batch :info.snomed/LanguageRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 2 ser/write-language-refset-item))

(defmethod write-batch :info.snomed/SimpleMapRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 3 ser/write-simple-map-refset-item))

(defmethod write-batch :info.snomed/ComplexMapRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 4 ser/write-complex-map-refset-item))

(defmethod write-batch :info.snomed/ExtendedMapRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 5 ser/write-extended-map-refset-item))

(defmethod write-batch :info.snomed/OwlExpressionRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 6 ser/write-owl-expression-refset-item))

(defmethod write-batch :info.snomed/AttributeValueRefset [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 7 ser/write-attribute-value-refset-item))

(defmethod write-batch :info.snomed/RefsetDescriptor [batch store]
  (put-refset-items (.-env store) (.-refsetItems store) (:data batch) 8 ser/write-refset-descriptor-refset-item))


(comment

  (def store (open-store (java.io.File. "wibble.db") {:read-only? false}))
  (.close (.env store))
  (require '[com.eldrix.hermes.import :as imp])
  (imp/importable-files "/Users/mark/Downloads/snomed-2021-01")
  (imp/all-metadata "/Users/mark/Downloads/snomed-2021-01")
  (def ch (imp/load-snomed "/Users/mark/Downloads/snomed-2021-01" :batch-size 50000))

  (java.time.LocalDateTime/now)
  (loop [batch (a/<!! ch)]
    (when batch
      (write-batch batch store)
      (recur (a/<!! ch))))

  (get-concept store 24700007)

  (.entries (.stat (.concepts store) (.txnRead (.env store))))
  (.entries (.stat (.descriptions store) (.txnRead (.env store))))
  (.entries (.stat (.descriptionsConcept store) (.txnRead (.env store))))
  (.entries (.stat (.relationships store) (.txnRead (.env store))))

  (def store (open-store (java.io.File. "wibble.db") {:read-only? false}))
  (def txn (.txnWrite (.env store)))
  (def cursor (.openCursor (.concepts store) txn))
  (def kbb (.buffer ByteBufAllocator/DEFAULT 16))
  (def vbb (.buffer ByteBufAllocator/DEFAULT 1024))
  (def out (ByteBufOutputStream. vbb))
  (.writeLong kbb 24700007)
  (ser/write-concept out (snomed/->Concept 24700007 (LocalDate/now) true 0 0))
  (.put cursor kbb vbb (make-array PutFlags 0))
  (.clear vbb)
  (.clear kbb)
  (.commit txn)
  (.release kbb)
  (.release vbb)
  (get-concept store 108537001)

  (should-write? cursor kbb 8 (.minusDays (LocalDate/now) 5))
  (.close (.env store))

  (time (get-description store 1223979019))
  (def txn (.txnRead (.env store)))
  (def kbb (.buffer ByteBufAllocator/DEFAULT 16))
  (.writeLong kbb 41398015)
  (def concept-id-bb (.get ^Dbi (.-descriptionsConcept store) txn kbb))
  (.readLong concept-id-bb)
  (.clear kbb)
  (.writeBytes kbb concept-id-bb)
  (.writeLong kbb 41398015)
  (def desc-bb (.get (.-descriptions store) txn kbb))
  kbb
  (ser/read-description (ByteBufInputStream. desc-bb))


  (def cursor (.openCursor (.-descriptionsConcept store) txn))
  (.writeLong kbb 41398015)
  (.get cursor kbb GetOp/MDB_SET)
  (.close cursor)
  (.close txn)
  )
