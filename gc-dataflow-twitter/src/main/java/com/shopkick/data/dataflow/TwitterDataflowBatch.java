package com.shopkick.data.dataflow;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.UnboundedSource;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.options.Validation;
import com.google.cloud.dataflow.sdk.options.DefaultValueFactory;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.Filter;
import com.google.cloud.dataflow.sdk.transforms.Top;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.SlidingWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.transforms.windowing.AfterWatermark;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PDone;

import java.util.Properties;
import java.util.List;
import com.google.api.services.bigquery.model.TableRow;

import org.json.JSONObject;
import org.json.JSONArray;

import org.joda.time.Duration;
import org.joda.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Twitter entities count pipeline
 */
public class TwitterDataflowBatch {

  static final long WINDOW_SIZE = 600;  // Default window duration in seconds
  static final long WINDOW_SLIDE = 10;  // Default window slide in seconds

  static final String INPUT = "theneeds0:bigquery0.oscars2016";  // Default PubSub topic to read from
  //static final String OUTPUT = "/topics/theneeds0/twoutrecover";  // Default PubSub topic to write to

  static final String FILTER = "#oscars2016,#oscars,#redcarpet,#oscar2016,#oscar,#oscarsdata,@theacademy";
  
  /** Extracts entities (hashtags and mentions) from a tweet.
      Input is a json string.
      Supports quoted_status, emitted with the quoting tweet timestamp.
      TODO: use jackson
  */
  static class ExtractEntitiesFn extends DoFn<String, String>{

    //private static final Logger LOG = LoggerFactory.getLogger(ExtractEntitiesFn.class);

    @Override
    public void processElement(ProcessContext c) {

      String in = c.element();
      JSONObject tweet = new JSONObject(in);

      Instant timestamp = new Instant(tweet.getLong("timestamp_ms"));
      //LOG.debug("Timestamp: " + timestamp);

      this.processTweet(c, tweet, timestamp);
      
      if (!tweet.isNull("quoted_status")) {
        //LOG.debug("Found quoted_status");
        JSONObject quotedTweet = tweet.getJSONObject("quoted_status");
        this.processTweet(c, quotedTweet, timestamp);
      }
    }

    protected void processTweet(ProcessContext c, JSONObject tweet, Instant timestamp) {

      JSONObject entities = tweet.getJSONObject("entities");

      JSONArray mentions = entities.getJSONArray("user_mentions");
      for (int i = 0; i < mentions.length(); i++) {
        JSONObject mention = mentions.getJSONObject(i);
        //LOG.debug("Mention: " + "@"+mention.getString("screen_name").toLowerCase());
        //c.outputWithTimestamp("@"+mention.getString("screen_name").toLowerCase(), timestamp);
        c.output("@"+mention.getString("screen_name").toLowerCase());
      }

      JSONArray hashtags = entities.getJSONArray("hashtags");
      for (int i = 0; i < hashtags.length(); i++) {
        JSONObject hashtag = hashtags.getJSONObject(i);
        //LOG.debug("Hashtag: " + "#"+hashtag.getString("text").toLowerCase());
        //c.outputWithTimestamp("#"+hashtag.getString("text").toLowerCase(), timestamp);
        c.output("#"+hashtag.getString("text").toLowerCase());
      }      
    }

    @Override
    public Duration getAllowedTimestampSkew(){
      return Duration.millis(Long.MAX_VALUE);
    }
  }

  /** Converts a windowed (Entity, Count) into a printable 
      comma separated string: window end, entity, count.
      TODO: change to SimpleFunction when support for Dataflow 1.4 is available.
  */
  public static class FormatAsTextFn extends DoFn<KV<String, Long>, String> implements DoFn.RequiresWindowAccess{
    @Override
    public void processElement(ProcessContext c) {
      String text = ((IntervalWindow)c.window()).end() + "," + c.element().getKey() + "," + c.element().getValue();
      c.output(text);
    }
  }

  public static class FormatAsTextTopFn extends DoFn<List<KV<String, Long>>, String> implements DoFn.RequiresWindowAccess{
    @Override
    public void processElement(ProcessContext c) {
      String text = "" + ((IntervalWindow)c.window()).end();
      for (KV<String, Long> i:c.element()){
        text +=  "," + i.getKey() + "," + i.getValue();
      }
      c.output(text);
    }
  }

  static class ExtractRowsFn extends DoFn<TableRow, String>{
    @Override
    public void processElement(ProcessContext c){
      String in = (String)c.element().get("json");
      JSONObject tweet = new JSONObject(in);
      Instant timestamp = new Instant(tweet.getLong("timestamp_ms"));
      c.outputWithTimestamp(in,timestamp);
    }

/*    @Override
    public Duration getAllowedTimestampSkew(){
      return Duration.millis(Long.MAX_VALUE);
    }*/
  }

  static class FilterIn implements SerializableFunction<KV<String,Long>,Boolean>{
    private String[] list;
    private Boolean in;

    // - list: String, comma separated list of String to filter
    // - in: Control exclusion/inclusion. If False filter out the entity in the list,
    //       If True filter in
    public FilterIn(String list, Boolean in){
      if (list == null){
        this.list = null;
      }else{
        this.list = list.split(",");
        this.in = in;
      }
    }

    public FilterIn(String list){
      if (list == null){
        this.list = null;
      }else{
        this.list = list.split(",");
        this.in = true;
      }
    }

    public Boolean apply(KV<String, Long> input){
      if (this.list == null)
        return true;
      for (String e: this.list) {
        if (input.getKey().equalsIgnoreCase(e))
          return in;
      }
      return !in;
    }

  }

  /**
   * Options supported by {@link TwitterDataflow}.
   * Inherits standard configuration options, and flink-dataflow options.
   */
  public static interface TwitterDataflowOptions extends PipelineOptions, DataflowPipelineOptions {
    @Description("Pubsub topic to read from")
    @Default.String("2016-02-28 22:00:00")
    String getInputStart();
    void setInputStart(String value);

    @Description("Pubsub topic to read from")
    @Default.String("2016-02-29 06:00:00")
    String getInputStop();
    void setInputStop(String value);

    @Description("File to save to. The format should be gs://<bucket>/<path>")
    @Validation.Required
    String getSaveTo();
    void setSaveTo(String value);

    @Description("File to save to. The format should be gs://<bucket>/<path>")
    @Validation.Required
    String getSaveToTop();
    void setSaveToTop(String value);

    @Description("Entities to filter out in the top 10")
    @Default.String(FILTER)
    String getFilterEntities();
    void setFilterEntities(String value);

    @Description("Sliding window duration, in seconds")
    @Default.Long(WINDOW_SIZE)
    Long getWindowSize();
    void setWindowSize(Long value);

    @Description("Window slide, in seconds")
    @Default.Long(WINDOW_SLIDE)
    Long getWindowSlide();
    void setWindowSlide(Long value);
  }


  public static void main(String[] args) {

    PipelineOptionsFactory.register(TwitterDataflowOptions.class);

    TwitterDataflowOptions options = PipelineOptionsFactory.fromArgs(args).withValidation()
      .as(TwitterDataflowOptions.class);

    // Cloud Dataflow specific options
    options.setJobName("TwitterDataflowClean");
    options.setStreaming(false);
    options.setNumWorkers(8);
    
    Pipeline pipeline = Pipeline.create(options);

    String query = "SELECT * FROM [bigquery0.oscars2016] WHERE created_at > '" 
      + options.getInputStart() + "' AND created_at < '" 
      + options.getInputStop() + "';";

    PCollection<KV<String,Long>> counted_data = pipeline
      .apply("Input", BigQueryIO.Read.fromQuery(query))
      .apply("Extract Table", ParDo.of(new ExtractRowsFn()))
      .apply("Extract", ParDo.of(new ExtractEntitiesFn()))
      .apply("Window", Window.<String>into(SlidingWindows.of(Duration.standardSeconds(options.getWindowSize()))
            .every(Duration.standardSeconds(options.getWindowSlide()))
          )
          .triggering(AfterWatermark.pastEndOfWindow()).withAllowedLateness(Duration.ZERO)
          .discardingFiredPanes()
        )
      .apply("Count", Count.<String>perElement());
    counted_data
      .apply("Format", ParDo.of(new FormatAsTextFn()))
      .apply("Output", TextIO.Write.to(options.getSaveTo())
        .withoutSharding())
      ;
    counted_data
      .apply("Filter", Filter.by(new FilterIn(options.getFilterEntities(),false)))
      .apply("Top10",
        Top.<KV<String,Long>,KV.OrderByValue<String,Long>>of(
          10,new KV.OrderByValue<String,Long>()).withoutDefaults())
      .apply("FormatTop10",ParDo.of(new FormatAsTextTopFn()))
      .apply("OutputTop10", TextIO.Write.to(options.getSaveToTop())
        .withoutSharding());

    pipeline.run();
  }
}
