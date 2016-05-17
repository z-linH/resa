package resa.evaluation.topology.tomVLD;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_nonfree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static resa.evaluation.topology.tomVLD.Constants.*;
import static resa.evaluation.topology.tomVLD.StormConfigManager.getInt;
import static resa.evaluation.topology.tomVLD.StormConfigManager.getListOfStrings;

/**
 * Created by Tom Fu at Aug 5, 2015
 * We try to enable to detector more than one logo template file
 * Enable sampling, add sampleID
 * move to Fox version
 */
public class PatchProcessorForExp extends BaseRichBolt {
    OutputCollector collector;
    private List<StormVideoLogoDetectorGamma> detectors;

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        int minNumberOfMatches = Math.min(getInt(map, "minNumberOfMatches"), 4);
        this.collector = outputCollector;
        // TODO: get path to logos & parameters from config, different logo can use different threshold?
        Parameters parameters = new Parameters()
                .withMatchingParameters(
                        new Parameters.MatchingParameters()
                                .withMinimalNumberOfMatches(minNumberOfMatches)
                );

        List<String> templateFiles = getListOfStrings(map, "originalTemplateFileNames");
        int maxAdditionTemp = ConfigUtil.getInt(map, "maxAdditionTemp", 4);

        detectors = new ArrayList<>();
        for (int logoIndex = 0; logoIndex < templateFiles.size(); logoIndex ++) {
            detectors.add(new StormVideoLogoDetectorGamma(parameters, templateFiles.get(logoIndex), logoIndex, maxAdditionTemp));
        }
        System.out.println("PatchProcessorForExp.prepare, with logos: " + detectors.size() + ", maxAdditionTemp: " + maxAdditionTemp);
        opencv_core.IplImage fk = new opencv_core.IplImage();
    }

    @Override
    public void execute(Tuple tuple) {
        String streamId = tuple.getSourceStreamId();
        if (streamId.equals(SIFT_FEATURE_STREAM))
            processFrame(tuple);
        else if (streamId.equals(LOGO_TEMPLATE_UPDATE_STREAM))
            processNewTemplate(tuple);
        collector.ack(tuple);
    }

    private void processFrame(Tuple tuple) {
        int frameId = tuple.getIntegerByField(FIELD_FRAME_ID);
        int sampleID = tuple.getIntegerByField(FIELD_SAMPLE_ID);

        Serializable.PatchIdentifier identifier = (Serializable.PatchIdentifier) tuple.getValueByField(FIELD_PATCH_IDENTIFIER);
        int patchCount = tuple.getIntegerByField(FIELD_PATCH_COUNT);

        Serializable.KeyPoint sKeyPoints = (Serializable.KeyPoint)tuple.getValueByField(FIELD_SIFT_KEY_POINTS);
        Serializable.Mat sTestDescriptors = (Serializable.Mat)tuple.getValueByField(FIELD_SIFT_TDES_MAT);
        Serializable.Mat sRR = (Serializable.Mat)tuple.getValueByField(FIELD_SIFT_RR_MAT);
        Serializable.Rect sRoi = (Serializable.Rect) tuple.getValueByField(FIELD_SIFT_ROI);
        SIFTfeatures sifTfeatures = new SIFTfeatures(sKeyPoints, sTestDescriptors, sRR, sRoi);

        List<Serializable.Rect> detectedLogoList = new ArrayList<>();

        for (int logoIndex = 0; logoIndex < detectors.size(); logoIndex ++) {
            StormVideoLogoDetectorGamma detector = detectors.get(logoIndex);
            detector.detectLogosByFeatures(sifTfeatures);

            Serializable.Rect detectedLogo = detector.getFoundRect();
            Serializable.Mat extractedTemplate = detector.getExtractedTemplate();
            if (detectedLogo != null) {
                collector.emit(LOGO_TEMPLATE_UPDATE_STREAM,
                        new Values(identifier, extractedTemplate, detector.getParentIdentifier(), logoIndex));
            }

            detectedLogoList.add(detectedLogo);
        }
        collector.emit(DETECTED_LOGO_STREAM, tuple, new Values(frameId, detectedLogoList, patchCount, sampleID));
    }

    private void processNewTemplate(Tuple tuple) {
        Serializable.PatchIdentifier receivedPatchIdentifier = (Serializable.PatchIdentifier) tuple.getValueByField(FIELD_HOST_PATCH_IDENTIFIER);
        Serializable.Mat extracted = (Serializable.Mat) tuple.getValueByField(FIELD_EXTRACTED_TEMPLATE);
        Serializable.PatchIdentifier parent = (Serializable.PatchIdentifier) tuple.getValueByField(FIELD_PARENT_PATCH_IDENTIFIER);

        int logoIndex = tuple.getIntegerByField(FIELD_LOGO_INDEX);

        detectors.get(logoIndex).addTemplateBySubMat(receivedPatchIdentifier, extracted);
        detectors.get(logoIndex).incrementPriority(parent, 1);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(DETECTED_LOGO_STREAM, new Fields(FIELD_FRAME_ID, FIELD_FOUND_RECT, FIELD_PATCH_COUNT, FIELD_SAMPLE_ID));

        outputFieldsDeclarer.declareStream(LOGO_TEMPLATE_UPDATE_STREAM,
                new Fields(FIELD_HOST_PATCH_IDENTIFIER, FIELD_EXTRACTED_TEMPLATE, FIELD_PARENT_PATCH_IDENTIFIER, FIELD_LOGO_INDEX));
    }
}