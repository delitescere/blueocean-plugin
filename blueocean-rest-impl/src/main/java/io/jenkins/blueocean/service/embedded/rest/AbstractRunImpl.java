package io.jenkins.blueocean.service.embedded.rest;

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Collections2;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.blueocean.commons.ServiceException;
import io.jenkins.blueocean.rest.Reachable;
import io.jenkins.blueocean.rest.factory.BlueTestResultFactory;
import io.jenkins.blueocean.rest.hal.Link;
import io.jenkins.blueocean.rest.hal.LinkResolver;
import io.jenkins.blueocean.rest.hal.Links;
import io.jenkins.blueocean.rest.model.BlueActionProxy;
import io.jenkins.blueocean.rest.model.BlueArtifactContainer;
import io.jenkins.blueocean.rest.model.BlueChangeSetEntry;
import io.jenkins.blueocean.rest.model.BlueOrganization;
import io.jenkins.blueocean.rest.model.BluePipelineNodeContainer;
import io.jenkins.blueocean.rest.model.BluePipelineStepContainer;
import io.jenkins.blueocean.rest.model.BlueRun;
import io.jenkins.blueocean.rest.model.BlueTestResultContainer;
import io.jenkins.blueocean.rest.model.BlueTestSummary;
import io.jenkins.blueocean.rest.model.Container;
import io.jenkins.blueocean.rest.model.Containers;
import io.jenkins.blueocean.rest.model.GenericResource;
import jenkins.util.SystemProperties;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Basic {@link BlueRun} implementation.
 *
 * @author Vivek Pandey
 */
public abstract class AbstractRunImpl<T extends Run> extends BlueRun {

    public static final String BLUEOCEAN_FEATURE_RUN_DESCRIPTION_ENABLED = "blueocean.feature.run.description.enabled";

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final Logger LOGGER = LoggerFactory.getLogger( AbstractRunImpl.class.getName());

    private static final long TEST_SUMMARY_CACHE_MAX_SIZE = Long.getLong("TEST_SUMMARY_CACHE_MAX_SIZE", 10000);
    private static final Cache<String, Optional<BlueTestSummary>> TEST_SUMMARY = CacheBuilder.newBuilder()
        .maximumSize(TEST_SUMMARY_CACHE_MAX_SIZE)
        .expireAfterAccess(1, TimeUnit.DAYS)
        .build();

    protected final T run;
    protected final BlueOrganization organization;

    protected final Reachable parent;
    public AbstractRunImpl(T run, Reachable parent, BlueOrganization organization) {
        this.run = run;
        this.parent = parent;
        this.organization = organization;
    }

    @Nonnull
    public Container<BlueChangeSetEntry> getChangeSet() {
        return Containers.empty(getLink());
    }

    @Override
    public String getOrganization() {
        return organization.getName();
    }

    @Override
    public String getId() {
        return run.getId();
    }

    @Override
    public String getPipeline() {
        return run.getParent().getName();
    }

    @Override
    public String getName() {
        String defaultName = "#"+run.getNumber();
        String displayName = run.getDisplayName();
        return defaultName.equals(displayName) ? null : displayName;
    }

    @Override
    public String getDescription() {
        return SystemProperties.getBoolean(BLUEOCEAN_FEATURE_RUN_DESCRIPTION_ENABLED, true) ? run.getDescription() : null;
    }

    @Override
    public Date getStartTime() {
        return new Date(run.getStartTimeInMillis());
    }

    @Override
    public Date getEnQueueTime() {
        return new Date(run.getTimeInMillis());
    }

    @Override
    public String getEnQueueTimeString() {
        return DATE_FORMAT.print(getEnQueueTime().getTime());
    }

    @Override
    public String getStartTimeString(){
        return DATE_FORMAT.print(getStartTime().getTime());
    }

    @Override
    public String getEndTimeString(){
        Date endTime = getEndTime();
        if(endTime == null) {
            return null;
        } else {
            return DATE_FORMAT.print(endTime.getTime());
        }
    }

    @Override
    public BlueRunState getStateObj() {
        if(!run.hasntStartedYet() && run.isLogUpdated()) {
            return BlueRunState.RUNNING;
        } else if(!run.isLogUpdated()){
            return BlueRunState.FINISHED;
        } else {
            return BlueRunState.RUNNING;
        }
    }

    @Override
    public BlueRunResult getResult() {
        // A runs result is always unknown until it has finished running
        if (getStateObj() == BlueRunState.RUNNING) {
            return BlueRunResult.UNKNOWN;
        } else {
            Result result = run.getResult();
            return result != null ? BlueRunResult.valueOf(result.toString()) : BlueRunResult.UNKNOWN;
        }
    }


    @Override
    public Date getEndTime() {
        if (!run.isBuilding()) {
            return new Date(run.getStartTimeInMillis() + run.getDuration());
        }
        return null;
    }

    @Override
    public Long getDurationInMillis() {
        return run.getDuration();
    }

    @Override
    public Long getEstimatedDurtionInMillis() {
        return run.getEstimatedDuration();
    }

    @Override
    public String getRunSummary() {
        return run.getBuildStatusSummary().message;
    }

    @Override
    public String getType() {
        return run.getClass().getSimpleName();
    }

    @Override
    public Object getLog() {
        return new LogResource(run.getLogText());
    }

    @Override
    public BlueRun replay() {
        return null;
    }

    @Override
    public Collection<BlueCause> getCauses() {
        return BlueCauseImpl.getCauses(this.run);
    }

    @Override
    public String getCauseOfBlockage() {
        return null;
    }

    @Override
    public boolean isReplayable() {
        return false;
    }

    @Override
    public BlueArtifactContainer getArtifacts() {
       return new ArtifactContainerImpl(run, this);
    }

    @Override
    public BluePipelineNodeContainer getNodes() {
        return null; // default
    }

    @Override
    public BluePipelineStepContainer getSteps() {
        return null;
    }

    @Override
    public BlueTestResultContainer getTests() {
        return new BlueTestResultContainerImpl(this, run);
    }

    @Override
    public BlueTestSummary getTestSummary() {
        return null;
    }

    @Override
    public BlueTestSummary getBlueTestSummary() {
        BlueTestSummary blueTestSummary;
        if (getStateObj() == BlueRunState.FINISHED) {
            try {
                blueTestSummary =  TEST_SUMMARY.get(run.getExternalizableId(),  () -> {
                                            LOGGER.debug( "load test summary {} thread {}", //
                                                          run.getExternalizableId(), //
                                                          Thread.currentThread().getName() );
                                            BlueTestSummary summary = BlueTestResultFactory.resolve(run, parent).summary;
                                            return summary == null ? Optional.absent() : Optional.of(summary);
                                        }
                ).orNull();
            } catch (ExecutionException e) {
                LOGGER.error("Could not load test summary from cache", e);
                return null;
            }
        } else {
            blueTestSummary =  BlueTestResultFactory.resolve(run, this).summary;
            if (blueTestSummary == null) {
                // Just use an empty one while we wait
                blueTestSummary = new BlueTestSummary(0,0,0,0,0,0,0,this.getLink());
            }
        }

        // .../runs/123/blueTestSummary
        if (blueTestSummary == null)
        {
            return null;
        }
        Link link = this.getLink().rel("blueTestSummary");
        blueTestSummary.setLink( link );
        return blueTestSummary;
    }

    public Collection<BlueActionProxy> getActions() {
        return ActionProxiesImpl.getActionProxies(run.getAllActions(), this);
    }

    @Override
    public BlueRun stop(@QueryParameter("blocking") Boolean blocking, @QueryParameter("timeOutInSecs") Integer timeOutInSecs){
        throw new ServiceException.NotImplementedException("Stop should be implemented on a subclass");
    }

    @Override
    public String getArtifactsZipFile() {
        if(run.getHasArtifacts()) {
            return "/" + run.getUrl() + "artifact/*zip*/archive.zip";
        } else {
            return null;
        }
    }

    protected BlueRun stop(Boolean blocking, Integer timeOutInSecs, StoppableRun stoppableRun){
            if(blocking == null){
                blocking = false;
            }
            try {
                long start = System.currentTimeMillis();
                if(timeOutInSecs == null){
                    timeOutInSecs = DEFAULT_BLOCKING_STOP_TIMEOUT_IN_SECS;
                }
                if(timeOutInSecs < 0){
                    throw new ServiceException.BadRequestException("timeOutInSecs must be >= 0");
                }

                stoppableRun.stop();

                long timeOutInMillis = timeOutInSecs*1000;
                long sleepingInterval = timeOutInMillis/10; //one tenth of timeout
                do{
                    if(isCompletedOrAborted()){
                        return this;
                    }
                    Thread.sleep(sleepingInterval);
                }while(blocking && (System.currentTimeMillis() - start) < timeOutInMillis);

            } catch (Exception e) {
                throw new ServiceException.UnexpectedErrorException(String.format("Failed to stop run %s: %s", run.getId(), e.getMessage()), e);
            }
        return this;
    }

    /**
     * Handles HTTP path handled by actions or other extensions
     *
     * @param token path token that an action or extension can handle
     *
     * @return action or extension that handles this path.
     */
    public Object getDynamic(String token) {
        for (Action a : run.getAllActions()) {
            if (token.equals(a.getUrlName()))
                return new GenericResource<>(a);
        }

        return null;
    }

    @Override
    public Link getLink() {
        if(parent == null){
            return organization.getLink().rel(String.format("pipelines/%s/runs/%s", run.getParent().getName(), getId()));
        }
        return parent.getLink().rel("runs/"+getId());
    }

    private boolean isCompletedOrAborted(){
        Result result = run.getResult();
        return result != null && (result == Result.ABORTED || result.isCompleteBuild());
    }

    @Override
    public Links getLinks() {
        Links links = super.getLinks().add("parent", parent.getLink());
        Run nextRun = run.getNextBuild();
        Run prevRun = run.getPreviousBuild();

        if(nextRun != null) {
            Link nextRunLink = LinkResolver.resolveLink(nextRun);
            links.add("nextRun", nextRunLink);
        }

        if(prevRun != null) {
            Link prevRunLink = LinkResolver.resolveLink(prevRun);
            links.add("prevRun", prevRunLink);
        }

        return links;
    }

    public static class BlueCauseImpl extends BlueCause {

        private final hudson.model.Cause cause;

        BlueCauseImpl(hudson.model.Cause cause) {
            this.cause = cause;
        }

        @Override
        public String getShortDescription() {
            return cause.getShortDescription();
        }

        @Override
        public Object getCause() {
            return cause;
        }

        static Collection<BlueCause> getCauses(Run run) {
            CauseAction action = run.getAction(CauseAction.class);
            if (action == null) {
                return null;
            }
            return getCauses(action.getCauses());
        }

        static Collection<BlueCause> getCauses(Collection<hudson.model.Cause> causes) {
            return causes.stream().map( input -> new BlueCauseImpl(input)).collect(Collectors.toList());
        }
    }
}
