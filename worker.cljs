(ns worker
  (:require ["@aws-sdk/client-s3$default" :refer [S3]]
            ["axios$default" :as axios]
            ["child_process" :as child_process]
            ["dotenv" :as dotenv]
            ["fs" :as fs]
            ["path" :as path]
            ["util" :as util]
            [promesa.core :as p]
            ["sqs-consumer" :refer [Consumer]]))

(def s3 (S3. #js {:region "us-east-1"}))

(dotenv/config)

(def output-bucket js/process.env.OUTPUT_BUCKET)
(def output-url js/process.env.OUTPUT_URL)
(def slack-webhook js/process.env.SLACK_WEBHOOK)
(def work-queue-url js/process.env.WORK_QUEUE_URL)

(def exec (util/promisify child_process/exec))

(defn generate-image [prompt]
  (p/let [output (exec (str "./build.sh --skip --prompt \"" prompt "\""))]
    output))

(defn upload-image-to-s3 [path]
  (let [params #js {:Bucket output-bucket
                    :Key (path/basename path)
                    :Body (fs/createReadStream path)
                    :ContentType "image/png"}]
    (js/console.log params)
    (.putObject s3 params)))

(defn post-image-to-slack [image]
  (axios #js {:method "POST"
              :url slack-webhook
              :data #js {:text (str output-url "/" image)}}))

(defn handle-message [msg]
  (let [prompt (.-Body msg)]
    (p/do 
      (generate-image prompt)
      (let [files (.readdirSync fs "output")
            image (first files)
            path (str "output/" image)]
        (p/do
          (upload-image-to-s3 path)
          (post-image-to-slack image)
          (.unlinkSync fs path))))))

(def consumer (.create Consumer
                       #js {:queueUrl work-queue-url
                            :handleMessage handle-message}))

(.start consumer)
