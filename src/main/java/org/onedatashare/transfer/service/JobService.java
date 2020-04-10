package org.onedatashare.transfer.service;

import org.onedatashare.transfer.model.core.Job;
import org.onedatashare.transfer.model.core.JobDetails;
import org.onedatashare.transfer.model.jobaction.JobRequest;
import org.onedatashare.transfer.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@Service
public class JobService {

    @Autowired
    private UserService userService;

    @Autowired
    private JobRepository jobRepository;

    private static Pageable generatePageFromRequest(JobRequest request){
        Sort.Direction direction = request.sortOrder.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        request.pageNo = Math.max(request.pageNo, 0);
        Pageable page = PageRequest.of(request.pageNo, request.pageSize, Sort.by(direction, request.sortBy));
        return page;
    }

    public Mono<Job> getJobByUUID(UUID uuid) {
        return jobRepository.findById(uuid);
    }

    public Mono<List<Job>> getAllJobsForUser(String cookie) {
        return userService.getJobs().flatMap(this::getJobByUUID).publishOn(Schedulers.parallel())
                .collectList();
    }

    public Mono<JobDetails> getJobsForUser(JobRequest request) {
        Pageable pageable = this.generatePageFromRequest(request);
        return userService.getLoggedInUserEmail().flatMap(userEmail -> {
            Mono<List<Job>> jobList = jobRepository.findJobsForUser(userEmail, pageable).collectList();
            Mono<Long> jobCount = jobRepository.getJobCountForUser(userEmail);
            return jobList.zipWith(jobCount, JobDetails::new);
        });
    }

    public Mono<JobDetails> getJobForAdmin(JobRequest request){
        Pageable pageable = this.generatePageFromRequest(request);
        return userService.getLoggedInUserEmail().flatMap(userEmail -> {
            Mono<List<Job>> jobList = jobRepository.findAllBy(pageable).collectList();
            Mono<Long> jobCount = jobRepository.getCount();
            return jobList.zipWith(jobCount, JobDetails::new);
        });
    }

    public Mono<List<Job>> getUpdatesForUser(List<UUID> jobIds){
        return userService.getLoggedInUserEmail()
                .flatMap(userEmail ->
                        jobRepository.findAllById(jobIds)
                                .filter(job -> !job.isDeleted() && job.getOwner().equals(userEmail))
                                .collectList()
                );
    }

    public  Flux<Job> getUpdatesForAdmin(List<UUID> jobIds){
        return jobRepository.findAllById(jobIds);
    }

    public Mono<Job> findJobByJobId(String cookie, Integer job_id) {
        return getAllJobsForUser(cookie).map(jobs -> {
            Job job = new Job(null, null);
            for(Job j: jobs) {
                if(j.getJob_id() == job_id) job = j;
            }
            return job;
        });
    }

    public Mono<Job> saveJob(Job job) {
        return jobRepository.save(job);
    }
}
