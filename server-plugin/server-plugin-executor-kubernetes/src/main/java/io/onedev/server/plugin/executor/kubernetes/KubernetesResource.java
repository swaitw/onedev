package io.onedev.server.plugin.executor.kubernetes;

import com.google.common.base.Splitter;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.K8sJobData;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.entitymanager.JobCacheManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.job.JobContext;
import io.onedev.server.job.JobManager;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.security.SecurityUtils;
import org.apache.commons.lang.SerializationUtils;
import org.apache.shiro.authz.UnauthorizedException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.onedev.k8shelper.KubernetesHelper.readInt;
import static io.onedev.k8shelper.KubernetesHelper.readString;

@Api(internal=true)
@Path("/k8s")
@Consumes(MediaType.WILDCARD)
@Singleton
public class KubernetesResource {

	private final JobManager jobManager;
	
	private final JobCacheManager jobCacheManager;
	
	private final SessionManager sessionManager;
	
	private final ProjectManager projectManager;
	
    @Context
    private HttpServletRequest request;
    
    @Inject
    public KubernetesResource(JobManager jobManager, JobCacheManager jobCacheManager, 
							  SessionManager sessionManager, ProjectManager projectManager) {
    	this.jobManager = jobManager;
		this.jobCacheManager = jobCacheManager;
    	this.sessionManager = sessionManager;
		this.projectManager = projectManager;
	}
    
	@Path("/job-data")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
    @GET
    public byte[] getJobData(@QueryParam("jobToken") String jobToken, 
							 @QueryParam("jobWorkspace") @Nullable String jobWorkspace) {
		JobContext jobContext = jobManager.getJobContext(jobToken, true);
		if (StringUtils.isNotBlank(jobWorkspace))
			jobManager.reportJobWorkspace(jobContext, jobWorkspace);	
		K8sJobData k8sJobData = new K8sJobData(
				jobContext.getJobExecutor().getName(), 
				jobContext.getRefName(),
				jobContext.getCommitId().name(), 
				jobContext.getActions());
		return SerializationUtils.serialize(k8sJobData);
    }
	
	@Path("/run-server-step")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response runServerStep(@QueryParam("jobToken") String jobToken, InputStream is) {
		JobContext jobContext = jobManager.getJobContext(jobToken, true);
		// Make sure we are not occupying a database connection here as we will occupy 
		// database connection when running step at project server side
		sessionManager.closeSession();
		try {
			StreamingOutput os = output -> {
				File filesDir = FileUtils.createTempDir();
				try {
					int length = readInt(is);
					List<Integer> stepPosition = new ArrayList<>();
					for (int i=0; i<length; i++) 
						stepPosition.add(readInt(is));
					
					Map<String, String> placeholderValues = new HashMap<>();
					length = readInt(is);
					for (int i=0; i<length; i++) 
						placeholderValues.put(readString(is), readString(is));
					
					TarUtils.untar(is, filesDir, false);
					
					Map<String, byte[]> outputFiles = jobManager.runServerStep(jobContext, 
							stepPosition, filesDir, placeholderValues, true, new TaskLogger() {

						@Override
						public void log(String message, String sessionId) {
							// While testing, ngrok.io buffers response and build can not get log entries 
							// timely. This won't happen on pagekite however
							KubernetesHelper.writeInt(output, 1);
							KubernetesHelper.writeString(output, message);
							try {
								output.flush();
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
						
					});
					if (outputFiles == null)
						outputFiles = new HashMap<>();
					byte[] bytes = SerializationUtils.serialize((Serializable) outputFiles); 
					KubernetesHelper.writeInt(output, 2);
					KubernetesHelper.writeInt(output, bytes.length);
					output.write(bytes);
				} finally {
					FileUtils.deleteDir(filesDir);
				}						
		   };
			return Response.ok(os).build();
		} finally {
			sessionManager.openSession();
		}
	}
	
	@Path("/download-dependencies")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadDependencies(@QueryParam("jobToken") String jobToken) {
		sessionManager.closeSession();
		try {
			StreamingOutput output = os -> {
				JobContext jobContext = jobManager.getJobContext(jobToken, true);
				File tempDir = FileUtils.createTempDir();
				try {
					jobManager.copyDependencies(jobContext, tempDir);
					TarUtils.tar(tempDir, os, false);
					os.flush();
				} finally {
					FileUtils.deleteDir(tempDir);
				}
			};
			return Response.ok(output).build();
		} finally {
			sessionManager.openSession();
		}
	}

	@Path("/download-cache")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	@GET
	public Response downloadCache(
			@QueryParam("jobToken") String jobToken,
			@QueryParam("cacheKey") @Nullable String cacheKey, 
			@QueryParam("cacheLoadKeys") @Nullable String joinedCacheLoadKeys, 
			@QueryParam("cachePath") String cachePath) {
		sessionManager.closeSession();
		try {
			StreamingOutput output = os -> {
				var jobContext = jobManager.getJobContext(jobToken, true);
				if (cacheKey != null) {
					jobCacheManager.downloadCache(jobContext.getProjectId(), cacheKey, cachePath, os);
				} else {
					var cacheLoadKeys = Splitter.on('\n').splitToList(joinedCacheLoadKeys);
					jobCacheManager.downloadCache(jobContext.getProjectId(), cacheLoadKeys, cachePath, os);
				}
			};
			return Response.ok(output).build();
		} finally {
			sessionManager.openSession();
		}
	}
	
	@Path("/upload-cache")
	@GET
	public Response checkUploadCache(
			@QueryParam("jobToken") String jobToken,
			@QueryParam("cacheKey") String cacheKey,
			@QueryParam("cachePath") String cachePath) {
		var jobContext = jobManager.getJobContext(jobToken, true);
		var project = projectManager.load(jobContext.getProjectId());
		if (project.isCommitOnBranch(jobContext.getCommitId(), project.getDefaultBranch())
				|| SecurityUtils.canUploadCache(project)) {
			return Response.ok().build();
		} else {
			throw new UnauthorizedException("Not authorized to upload cache");
		}
	}

	@Path("/upload-cache")
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	@POST
	public Response uploadCache(
			@QueryParam("jobToken") String jobToken,
			@QueryParam("cacheKey") String cacheKey, 
			@QueryParam("cachePath") String cachePath, 
			InputStream is) {
		checkUploadCache(jobToken, cacheKey, cachePath);
		var jobContext = jobManager.getJobContext(jobToken, true);
		sessionManager.closeSession();
		try {
			jobCacheManager.uploadCache(jobContext.getProjectId(), cacheKey, cachePath, is);
			return Response.ok().build();
		} finally {
			sessionManager.openSession();
		}
	}	
	
	@GET
	@Path("/test")
	public Response test() {
		String jobToken = SecurityUtils.getBearerToken(request);
		if (jobToken != null) 
			return Response.ok().build();
		else 
			return Response.status(400).entity("Missing job token").build();
	}
	
}
