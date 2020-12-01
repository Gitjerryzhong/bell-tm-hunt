package cn.edu.bnuz.bell.hunt

import cn.edu.bnuz.bell.hunt.cmd.ReviewTaskCommand
import cn.edu.bnuz.bell.organization.Teacher
import cn.edu.bnuz.bell.security.SecurityService
import cn.edu.bnuz.bell.service.DataAccessService
import cn.edu.bnuz.bell.workflow.State
import com.sun.glass.ui.EventLoop
import grails.gorm.transactions.Transactional
import org.springframework.beans.factory.annotation.Value

@Transactional
class ReviewTaskService {
    SecurityService securityService
    DataAccessService dataAccessService
    @Value('${bell.teacher.filesPath}')
    String filesPath

    def list() {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.startDate as startDate,
    rt.endDate as endDate,
    rt.type as type,
    rt.content as content,
    rt.ban as ban
)
from ReviewTask rt
order by rt.dateCreated desc
'''
    }

    def create(ReviewTaskCommand cmd) {
        def form = new ReviewTask(
                title: cmd.title,
                startDate: ReviewTaskCommand.toDate(cmd.startDate),
                endDate: ReviewTaskCommand.toDate(cmd.endDate),
                type: cmd.type as ReviewType,
                content: cmd.content,
                remind: cmd.remind,
                ban: cmd.ban as Level,
                attach: cmd.attach
        )
        if (!form.save()) {
           form.errors.each {
               println it
           }
        }
        return form
    }

    def getFormForShow(Long id) {
        def result = ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.startDate as startDate,
    rt.endDate as endDate,
    rt.type as type,
    rt.content as content,
    rt.remind as remind,
    rt.ban as ban,
    rt.attach as attach,
    rt.remind as remind
)
from ReviewTask rt
where rt.id = :id
''', [id: id]
        if (result) {
            Map task = result[0]
            if (task.type.toString() == 'APPLICATION' && task.ban) {
                task['banMe'] = existRunningProject(task.ban)
            }
            // 只有管理员可以看专家注意事项和专家
            if (!securityService.hasRole('ROLE_HUNT_ADMIN') && !securityService.hasRole('ROLE_HUNT_EXPERT')) {
                task.remind = null
            }
            return task
        }
    }

    private Integer existRunningProject(Level level) {
        dataAccessService.getInteger'''
select count(*) from Review r join r.project p where p.level = :level and p.principal.id = :userId
''', [level: level, userId: securityService.userId]
    }

    def update(Long id, ReviewTaskCommand cmd) {
        def form = ReviewTask.load(id)
        if (form) {
            // 先处理旧附件
            if (form.attach) {
                def basePath = "${filesPath}/review-task"
                form.attach.each{ String name ->
                    if (!cmd.attach.contains(name)) {
                        File file = new File(basePath, name)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                }
            }
            form.title = cmd.title
            form.startDate = ReviewTaskCommand.toDate(cmd.startDate)
            form.endDate = ReviewTaskCommand.toDate(cmd.endDate)
            form.type = cmd.type as ReviewType
            form.content = cmd.content
            form.remind = cmd.remind
            form.ban = cmd.ban as Level
            form.attach = cmd.attach
            form.save()
        }
        return form
    }

    def listForApproval() {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.endDate as endDate,
    rt.type as type,
    sum (case when r.reportType != 1 or r.status in (:passStates) then 1 else 0 end) as countProject,
    sum (case when r.status = 'CHECKED' then 1 else 0 end) as countUncheck,
    sum (case when r.status = 'FINISHED' and ((p.level = 'UNIVERSITY' and r.conclusionOfUniversity = 'OK') or (p.level = 'PROVINCE' and r.conclusionOfProvince = 'OK')) then 1 else 0 end) as countPass,
    sum (case when r.status = 'FINISHED' and ((p.level = 'UNIVERSITY' and r.conclusionOfUniversity = 'VETO') or (p.level = 'PROVINCE' and r.conclusionOfProvince = 'VETO')) then 1 else 0 end) as countFail
)
from Review r
join r.project p
right join r.reviewTask rt
group by rt.id, rt.title, rt.endDate, rt.type
order by rt.dateCreated desc
''', [passStates: [State.FINISHED, State.CHECKED]]
    }

    def countForApproval(Long taskId) {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    r.reportType as reportType,
    sum (case when r.reportType != 1 or r.status != 'CREATED' then 1 else 0 end) as countProject,
    sum (case when r.status = 'CHECKED' then 1 else 0 end) as countUncheck,
    sum (case when r.status = 'FINISHED' and r.conclusionOfUniversity = 'OK' then 1 else 0 end) as countPass,
    sum (case when r.status = 'FINISHED' and (r.conclusionOfUniversity = 'VETO' or r.conclusionOfUniversity = 'DELAY') then 1 else 0 end) as countFail
)
from Review r
join r.reviewTask rt
where rt.id = :taskId and r.status in (:passStates)
group by rt.id, r.reportType
order by r.reportType
''', [taskId: taskId, passStates: [State.FINISHED, State.CHECKED]]
    }

    def listForDepartment() {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.endDate as endDate,
    rt.type as type,
    sum (case when r.department.id = :departmentId and r.status != 'CREATED' then 1 else 0 end) as countProject,
    sum (case when r.department.id = :departmentId and r.status = 'SUBMITTED' then 1 else 0 end) as countUncheck,
    sum (case when r.department.id = :departmentId and r.status in (:passStates) then 1 else 0 end) as countPass,
    sum (case when r.department.id = :departmentId and r.status in (:failStates) then 1 else 0 end) as countFail,
    sum (case when r.department.id = :departmentId and r.status = 'FINISHED' then 1 else 0 end) as countFinal
)
from Review r
right join r.reviewTask rt
where (current_date between rt.startDate and rt.endDate) or r.department.id = :departmentId
group by rt.id, rt.title, rt.endDate, rt.type
order by rt.dateCreated desc
''', [departmentId: securityService.departmentId,
      passStates: [State.FINISHED, State.CHECKED],
      failStates: [State.REJECTED, State.CLOSED]]
    }

    def countForDepartment(Long taskId) {
        def result = Review.executeQuery '''
select new map(
    sum (case when r.status = 'SUBMITTED' and r.reportType in (2, 3) then 1 else 0 end) as countForCheck,
    sum (case when r.status = 'SUBMITTED' and r.reportType = 4 then 1 else 0 end) as countForKnot
)
from Review r
right join r.reviewTask rt
where rt.id = :taskId and r.department.id = :departmentId
group by r.department.id
''', [taskId: taskId, departmentId: securityService.departmentId]
        return result ? result[0] : [:]
    }

    /**
     * @return 已申请过的任务和申请项目数、正在开放的任务
     */
    def listForTeacher() {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.endDate as endDate,
    rt.type as type,
    rt.ban as ban,
    sum (case when p.principal = :teacher and r.status is not null and r.status <> 'CREATED' then 1 else 0 end) as countApplication
)
from Review r
right join r.reviewTask rt
left join r.project p
where (current_date between rt.startDate and rt.endDate)
or p.principal = :teacher
group by rt.id, rt.title, rt.endDate, rt.type, rt.ban
order by rt.dateCreated desc
''', [teacher: Teacher.load(securityService.userId)]
    }

    def listForExpert() {
        ReviewTask.executeQuery'''
select new map(
    rt.id as id,
    rt.title as title,
    rt.endDate as endDate,
    rt.type as type,
    count(*) as countForReview,
    sum (case when er.dateReviewed is null then 1 else 0 end) as countUnReview
)
from Review r
join r.reviewTask rt
join r.expertReview er
where er.expertId = :userId
group by rt.id, rt.title, rt.endDate, rt.type
''', [userId: securityService.userId]
    }

    def countForExpert(Long taskId) {
        def result = Review.executeQuery '''
select new map(
    sum (case when er.dateReviewed is null and r.reportType in (2, 3) then 1 else 0 end) as countForCheck,
    sum (case when er.dateReviewed is null and r.reportType = 4 then 1 else 0 end) as countForKnot
)
from Review r
join r.reviewTask rt
join r.expertReview er
where rt.id = :taskId and er.expertId = :userId
''', [taskId: taskId, userId: securityService.userId]
        return result ? result[0] : [:]
    }
}
