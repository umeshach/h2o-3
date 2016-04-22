package hex.aggregator;

import hex.DataInfo;
import hex.Model;
import hex.ModelCategory;
import hex.ModelMetrics;
import hex.pca.PCAModel;
import water.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;

import java.util.Arrays;

public class AggregatorModel extends Model<AggregatorModel,AggregatorModel.AggregatorParameters,AggregatorModel.AggregatorOutput> implements Model.ExemplarMembers {

  public static class AggregatorParameters extends Model.Parameters {
    public String algoName() { return "Aggregator"; }
    public String fullName() { return "Aggregator"; }
    public String javaName() { return AggregatorModel.class.getName(); }
    @Override public long progressUnits() { return 4; }

    public double _radius_scale=1.0;
    public DataInfo.TransformType _transform = DataInfo.TransformType.NORMALIZE; // Data transformation
    public PCAModel.PCAParameters.Method _pca_method = PCAModel.PCAParameters.Method.Power;   // Method for dimensionality reduction
    public int _k = 1;                     // Number of principal components
    public int _max_iterations = 1000;     // Max iterations for SVD
    public long _seed = System.nanoTime(); // RNG seed
    public boolean _use_all_factor_levels = false;   // When expanding categoricals, should first level be kept or dropped?
  }

  public static class AggregatorOutput extends Model.Output {
    public AggregatorOutput(Aggregator b) { super(b); }
    @Override public int nfeatures() { return _output_frame.get().numCols()-1/*counts*/; }
    @Override public ModelCategory getModelCategory() { return ModelCategory.Clustering; }

    public Key<Frame> _output_frame;
  }


  public Aggregator.Exemplar[] _exemplars;
  public long[] _counts;
  public Key _exemplar_assignment_vec_key;
  public Key _diKey;

  public AggregatorModel(Key selfKey, AggregatorParameters parms, AggregatorOutput output) { super(selfKey,parms,output); }

  @Override
  protected Frame predictScoreImpl(Frame orig, Frame adaptedFr, String destination_key, final Job j) {
    return null;
  }

  @Override
  protected Futures remove_impl(Futures fs) {
    _diKey.remove();
    _exemplar_assignment_vec_key.remove();
    return super.remove_impl(fs);
  }

  @Override
  public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    return null;
  }

  @Override
  protected double[] score0(double[] data, double[] preds) {
    return preds;
  }

  public Frame createFrameOfExemplars(Key destination_key) {
    final long[] keep = new long[_exemplars.length];
    for (int i=0;i<keep.length;++i)
      keep[i]=_exemplars[i].gid;

    // preserve the original row order
    Vec booleanCol = new MRTask() {
      @Override
      public void map(Chunk c, Chunk c2) {
        for (int i=0;i<keep.length;++i) {
          if (keep[i] < c.start()) continue;
          if (keep[i] >= c.start()+c._len) continue;
          c2.set((int)(keep[i]-c.start()), 1);
        }
      }
    }.doAll(new Frame(new Vec[]{(Vec)_exemplar_assignment_vec_key.get(), _parms.train().anyVec().makeZero()}))._fr.vec(1);

    Frame orig = _parms.train();
    Vec[] vecs = Arrays.copyOf(orig.vecs(), orig.vecs().length+1);
    vecs[vecs.length-1] = booleanCol;

    Frame ff = new Frame(orig.names(), orig.vecs());
    ff.add("predicate", booleanCol);
    Frame res = new Frame.DeepSelect().doAll(orig.types(),ff).outputFrame(destination_key, orig.names(), orig.domains());
    booleanCol.remove();
    assert(res.numRows()==_exemplars.length);

    Vec cnts = res.anyVec().makeZero();
    Vec.Writer vw = cnts.open();
    for (int i=0;i<_counts.length;++i)
      vw.set(i, _counts[i]);
    vw.close();
    res.add("counts", cnts);
    return res;
  }

  @Override
  public Frame scoreExemplarMembers(Key destination_key, final int exemplarIdx) {
    Vec booleanCol = new MRTask() {
      @Override
      public void map(Chunk c, NewChunk nc) {
        for (int i=0;i<c._len;++i)
          nc.addNum(c.at8(i)==_exemplars[exemplarIdx].gid ? 1 : 0,0);
      }
    }.doAll(Vec.T_NUM, new Frame(new Vec[]{(Vec)_exemplar_assignment_vec_key.get()})).outputFrame().anyVec();

    Frame orig = _parms.train();
    Vec[] vecs = Arrays.copyOf(orig.vecs(), orig.vecs().length+1);
    vecs[vecs.length-1] = booleanCol;

    Frame ff = new Frame(orig.names(), orig.vecs());
    ff.add("predicate", booleanCol);
    Frame res = new Frame.DeepSelect().doAll(orig.types(),ff).outputFrame(destination_key, orig.names(), orig.domains());
    assert(res.numRows()==_counts[exemplarIdx]);
    booleanCol.remove();
    return res;
  }
}