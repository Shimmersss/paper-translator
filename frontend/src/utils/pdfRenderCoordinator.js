function isRenderingCancelled(error) {
  return error?.name === 'RenderingCancelledException'
}

export function createPdfRenderCoordinator() {
  let generation = 0
  let activeTask = null

  async function cancelActiveTask() {
    const task = activeTask
    if (!task) return

    task.cancel()
    try {
      await task.promise
    } catch (error) {
      if (!isRenderingCancelled(error)) throw error
    } finally {
      if (activeTask === task) activeTask = null
    }
  }

  return {
    async prepare() {
      const token = ++generation
      await cancelActiveTask()
      return token
    },

    isCurrent(token) {
      return token === generation
    },

    attach(token, task) {
      if (token !== generation) {
        task.cancel()
        return false
      }
      activeTask = task
      return true
    },

    async wait(token, task) {
      try {
        await task.promise
      } catch (error) {
        if (token === generation && !isRenderingCancelled(error)) throw error
      } finally {
        if (activeTask === task) activeTask = null
      }
    },

    async invalidate() {
      generation += 1
      await cancelActiveTask()
    }
  }
}
