package cn.edu.bnuz.bell.hunt

import cn.edu.bnuz.bell.hunt.cmd.SubtypeCommand
import org.springframework.security.access.prepost.PreAuthorize

@PreAuthorize('hasRole("ROLE_HUNT_ADMIN")')
class SubtypeController {
	TypeService typeService

    def index() {
        renderJson(typeService.subtypeList())
    }

    def save() {
        SubtypeCommand cmd = new SubtypeCommand()
        bindData(cmd, request.JSON)
        println cmd
        def form = typeService.createSubtype(cmd)
        renderJson([id: form.id])
    }
}
