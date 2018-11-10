package cn.edu.bnuz.bell.hunt

import cn.edu.bnuz.bell.http.BadRequestException
import cn.edu.bnuz.bell.organization.Teacher
import cn.edu.bnuz.bell.security.User
import cn.edu.bnuz.bell.service.DataAccessService
import cn.edu.bnuz.bell.workflow.Activities
import cn.edu.bnuz.bell.workflow.DomainStateMachineHandler
import cn.edu.bnuz.bell.workflow.ListCommand
import cn.edu.bnuz.bell.workflow.ListType
import cn.edu.bnuz.bell.workflow.State
import cn.edu.bnuz.bell.workflow.WorkflowActivity
import cn.edu.bnuz.bell.workflow.WorkflowInstance
import cn.edu.bnuz.bell.workflow.Workitem
import cn.edu.bnuz.bell.workflow.commands.AcceptCommand
import cn.edu.bnuz.bell.workflow.commands.RejectCommand
import grails.gorm.transactions.Transactional

import javax.annotation.Resource

@Transactional
class ApplicationCheckService {
    ApplicationService applicationService
    @Resource(name='projectReviewStateMachine')
    DomainStateMachineHandler domainStateMachineHandler
    DataAccessService dataAccessService

    def list(String userId, Long taskId, ListType type) {
        switch (type) {
            case ListType.TODO:
                return findTodoList(userId, taskId)
            case ListType.DONE:
                return findDoneList(userId, taskId)
            case ListType.EXPR:
                return findFailList(userId, taskId)
            default:
                return allTypeList(userId, taskId)
        }
    }

    def allTypeList(String userId, Long taskId) {
        Review.executeQuery'''
select new map(
    application.id as id,
    project.name as name,
    project.principal.name as principalName,
    project.level as level,
    subtype.name as subtype,
    origin.name as origin,
    application.dateSubmitted as date,
    project.title as title,
    project.degree as degree,
    project.major as major,
    project.office as office,
    project.phone as phone,
    application.status as status
)
from Review application join application.project project
join project.subtype subtype
join project.origin origin
join application.department department
where department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
) and application.status != 'CREATED'
and application.reviewTask.id = :taskId
order by application.dateSubmitted
''', [userId: userId, taskId: taskId]
    }

    def findTodoList(String userId, Long taskId) {
        Review.executeQuery'''
select new map(
    application.id as id,
    project.name as name,
    project.principal.name as principalName,
    project.level as level,
    subtype.name as subtype,
    origin.name as origin,
    application.dateSubmitted as date,
    project.title as title,
    project.degree as degree,
    project.major as major,
    project.office as office,
    project.phone as phone,
    application.status as status
)
from Review application join application.project project
join project.subtype subtype
join project.origin origin
join application.department department
where department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
)  and application.status = :status
and application.reviewTask.id = :taskId
order by application.dateSubmitted
''', [userId: userId, status: State.SUBMITTED, taskId: taskId]
    }

    def findDoneList(String userId, Long taskId) {
        Review.executeQuery'''
select new map(
    application.id as id,
    project.name as name,
    project.principal.name as principalName,
    project.level as level,
    subtype.name as subtype,
    origin.name as origin,
    application.dateChecked as date,
    project.title as title,
    project.degree as degree,
    project.major as major,
    project.office as office,
    project.phone as phone,
    application.status as status
)
from Review application join application.project project
join project.subtype subtype
join project.origin origin
join application.checker checker
where checker.id = :userId
and application.status in (:status)
and application.reviewTask.id = :taskId
order by application.dateChecked desc
''', [userId: userId, status: [State.CHECKED, State.APPROVED], taskId: taskId]
    }

    def findFailList(String userId, Long taskId) {
        Review.executeQuery'''
select new map(
    application.id as id,
    project.name as name,
    project.principal.name as principalName,
    project.level as level,
    subtype.name as subtype,
    origin.name as origin,
    application.dateChecked as date,
    project.title as title,
    project.degree as degree,
    project.major as major,
    project.office as office,
    project.phone as phone,
    application.status as status
)
from Review application join application.project project
join project.subtype subtype
join project.origin origin
join application.checker checker
where checker.id = :userId
and application.status in (:status)
and application.reviewTask.id = :taskId
order by application.dateChecked desc
''', [userId: userId, status: [State.REJECTED, State.CLOSED], taskId: taskId]
    }

    void accept(String userId, AcceptCommand cmd, UUID workitemId) {
        Review application = Review.get(cmd.id)
        domainStateMachineHandler.accept(application, userId, Activities.CHECK, cmd.comment, workitemId, cmd.to)
        application.checker = Teacher.load(userId)
        application.dateChecked = new Date()
        application.save()
    }

    void reject(String userId, RejectCommand cmd, UUID workitemId) {
        Review application = Review.get(cmd.id)
        domainStateMachineHandler.reject(application, userId, Activities.CHECK, cmd.comment, workitemId)
        application.checker = Teacher.load(userId)
        application.dateChecked = new Date()
        application.save()
    }

    def getFormForReview(String userId, Long id, ListType type, UUID workitemId) {
        def form = applicationService.getFormInfo(id)

        def activity = Workitem.get(workitemId).activitySuffix
        domainStateMachineHandler.checkReviewer(id, userId, activity)

        return [
                form: form,
                workitemId: workitemId,
                prevId: getPrevReviewId(userId, id, type),
                nextId: getNextReviewId(userId, id, type),
        ]
    }

    def getFormForReview(String userId, Long id, ListType type) {
        def form = applicationService.getFormInfo(id)

        def workitem = Workitem.findByInstanceAndActivityAndToAndDateProcessedIsNull(
                WorkflowInstance.load(form.workflowInstanceId),
                WorkflowActivity.load("${Review.WORKFLOW_ID}.${Activities.CHECK}"),
                User.load(userId),
        )
        domainStateMachineHandler.checkReviewer(id, userId, Activities.CHECK)
        return [
                form: form,
                workitemId: workitem ? workitem.id : null,
                prevId: getPrevReviewId(userId, id, type),
                nextId: getNextReviewId(userId, id, type)
        ]
    }

    private Long getPrevReviewId(String userId, Long id, ListType type) {
        switch (type) {
            case ListType.TODO:
                return dataAccessService.getLong('''
select form.id
from Review form 
join form.reviewTask task
join form.department department
where current_date between task.startDate and task.endDate
and form.status = :status
and department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
) 
and form.dateSubmitted < (select dateSubmitted from Review where id = :id)
order by form.dateSubmitted desc
''', [userId: userId, id: id, status: State.SUBMITTED])
            case ListType.DONE:
                return dataAccessService.getLong('''
select form.id
from Review form
join form.department department
where department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
) 
and form.dateChecked is not null
and application.status in (:status)
and form.dateChecked > (select dateChecked from Review where id = :id)
order by form.dateChecked asc
''', [userId: userId, status: [State.REJECTED, State.CLOSED], id: id])
        }
    }

    private Long getNextReviewId(String userId, Long id, ListType type) {
        switch (type) {
            case ListType.TODO:
                return dataAccessService.getLong('''
select form.id
from Review form 
join form.reviewTask task
join form.department department
where current_date between task.startDate and task.endDate
and form.status = :status
and department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
) 
and form.dateSubmitted > (select dateSubmitted from Review where id = :id)
order by form.dateSubmitted asc
''', [userId: userId, id: id, status: State.SUBMITTED])
            case ListType.DONE:
                return dataAccessService.getLong('''
select form.id
from Review form
join form.department department
where department = (
  select checker.department
  from Checker checker
  join checker.teacher teacher
  where teacher.id = :userId
) 
and form.dateChecked is not null
and application.status in (:status)
and form.dateChecked < (select dateChecked from Review where id = :id)
order by form.dateChecked desc
''', [userId: userId, status: [State.REJECTED, State.CLOSED], id: id])
        }
    }
}
