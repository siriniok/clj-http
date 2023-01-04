(ns clj-http.multipart
  "Namespace used for clj-http to create multipart entities and bodies."
  (:import [java.io File InputStream]
           org.apache.http.Consts
           org.apache.http.entity.ContentType
           [org.apache.http.entity.mime HttpMultipartMode MultipartEntityBuilder]
           [org.apache.http.entity.mime.content ByteArrayBody ContentBody FileBody InputStreamBody StringBody]))

;; we don't need to make a fake byte-array every time, only once
(def byte-array-type (type (byte-array 0)))

(defmulti
  make-multipart-body
  "Create a body object from the given map, dispatching on the type of its
   content. By default supported content body types are:
   - String
   - byte array (requires providing name)
   - InputStream (requires providing name)
   - File
   - org.apache.http.entity.mime.content.ContentBody (which is just returned)"
  (fn [multipart] (type (:content multipart))))

(defmethod make-multipart-body nil
  [multipart]
  (throw (Exception. "Multipart content cannot be nil")))

(defmethod make-multipart-body :default
  [multipart]
  (throw (Exception. (str "Unsupported type for multipart content: "
                          (type (:content multipart))))))

(defmethod make-multipart-body File
  ;; Create a FileBody object from the given map, requiring at least :content
  [{:keys [^String name ^String mime-type ^File content ^String encoding]}]
  (cond
    (and name mime-type content encoding)
    (FileBody. content (ContentType/create mime-type encoding) name)

    (and mime-type content encoding)
    (FileBody. content (ContentType/create mime-type encoding))

    (and name mime-type content)
    (FileBody. content (ContentType/create mime-type) name)

    (and mime-type content)
    (FileBody. content (ContentType/create mime-type))

    content
    (FileBody. content)

    :else
    (throw (Exception. "Multipart file body must contain at least :content"))))

(defmethod make-multipart-body InputStream
  ;; Create an InputStreamBody object from the given map, requiring at least
  ;; :content and :name. If no :length is specified, clj-http will use
  ;; chunked transfer-encoding, if :length is specified, clj-http will
  ;; workaround things be proxying the InputStreamBody to return a length.
  [{:keys [^String name ^String mime-type ^InputStream content length]}]
  (cond
    (and content name length)
    (if mime-type
      (proxy [InputStreamBody] [content (ContentType/create mime-type) name]
        (getContentLength []
          length))
      (proxy [InputStreamBody] [content name]
        (getContentLength []
          length)))

    (and content mime-type name)
    (InputStreamBody. content (ContentType/create mime-type) name)

    (and content name)
    (InputStreamBody. content name)

    :else
    (throw (Exception. (str "Multipart input stream body must contain "
                            "at least :content and :name")))))

(defmethod make-multipart-body byte-array-type
  ;; Create a ByteArrayBody object from the given map, requiring at least
  ;; :content and :name.
  [{:keys [^String name ^String mime-type ^bytes content]}]
  (cond
    (and content name mime-type)
    (ByteArrayBody. content (ContentType/create mime-type) name)

    (and content name)
    (ByteArrayBody. content name)

    :else
    (throw (Exception. (str "Multipart byte array body must contain "
                            "at least :content and :name")))))

(defmulti  ^java.nio.charset.Charset encoding-to-charset class)
(defmethod encoding-to-charset nil [encoding] nil)
(defmethod encoding-to-charset java.nio.charset.Charset [encoding] encoding)
(defmethod encoding-to-charset java.lang.String [encoding]
  (java.nio.charset.Charset/forName encoding))

(defmethod make-multipart-body String
  ;; Create a StringBody object from the given map, requiring at least :content.
  ;; If :encoding is specified, it will be created using the Charset for that
  ;; encoding.
  [{:keys [^String mime-type ^String content encoding]}]
  (cond
    (and content mime-type encoding)
    (StringBody.
     content (ContentType/create mime-type (encoding-to-charset encoding)))

    (and content encoding)
    (StringBody.
     content (ContentType/create "text/plain" (encoding-to-charset encoding)))

    content
    (StringBody. content (ContentType/create "text/plain" Consts/UTF_8))))

(defmethod make-multipart-body ContentBody
  ;; Use provided org.apache.http.entity.mime.content.ContentBody directly
  [{:keys [^ContentBody content]}]
  content)

(defn- multipart-workaround
  "Workaround for AsyncHttpClient to bypass 25kb restriction on getContent.

  See https://github.com/dakrone/clj-http/issues/560.
  "
  [^org.apache.http.entity.mime.MultipartFormEntity mp-entity]
  (reify org.apache.http.HttpEntity
    (isRepeatable [_] (.isRepeatable mp-entity))
    (isChunked [_] (.isChunked mp-entity))
    (isStreaming [_] (.isStreaming mp-entity))
    (getContentLength [_] (.getContentLength mp-entity))
    (getContentType [_] (.getContentType mp-entity))
    (getContentEncoding [_] (.getContentEncoding mp-entity))
    (consumeContent [_] (.consumeContent mp-entity))
    (getContent [_]
      (let [os (java.io.ByteArrayOutputStream.)]
        (.writeTo mp-entity os)
        (.flush os)
        (java.io.ByteArrayInputStream. (.toByteArray os))))
    (writeTo [_ output-stream] (.writeTo mp-entity output-stream))))

(defn create-multipart-entity
  "Takes a multipart vector of maps and creates a MultipartEntity with each
  map added as a part, depending on the type of content."
  [multipart {:keys [mime-subtype multipart-mode multipart-charset boundary]
              :or {mime-subtype "form-data"
                   multipart-mode HttpMultipartMode/STRICT}}]
  (let [mp-entity (doto (MultipartEntityBuilder/create)
                    (.setMode multipart-mode)
                    (.setMimeSubtype mime-subtype))]
    (when boundary (.setBoundary mp-entity boundary))
    (when multipart-charset
      (.setCharset mp-entity (encoding-to-charset multipart-charset)))
    (doseq [m multipart]
      (let [name (or (:part-name m) (:name m))
            part (make-multipart-body m)]
        (.addPart mp-entity name part)))
    (multipart-workaround
     (.build mp-entity))))
